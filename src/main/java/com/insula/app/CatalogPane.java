package com.insula.app;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import com.insula.catalog.CatalogCache;
import com.insula.catalog.CatalogFilter;
import com.insula.catalog.CatalogGroups;
import com.insula.catalog.Paging;
import com.insula.catalog.ZimEntry;
import com.insula.download.ProgressSnapshot;

/**
 * The Catalog: a faceted, card-based browser over the cached OPDS feed.
 *
 * <p>Named Catalog, not Store: nothing here is for sale, and the source really is an OPDS
 * catalog. The rename covers the surface, the menus and the command ids ({@code catalog.*}).
 *
 * <p>Everything here runs against {@link CatalogCache} — filtering and search are local and
 * instant, and the network is touched only by the Refresh affordance (or the silent auto-refresh
 * when the cache is a week old). Offline, everything works except Download.
 *
 * <p>One card per title with the flavour as a segmented choice labelled by size — the size
 * <em>is</em> the meaning of the choice. Rendering is capped at {@link #MAX_CARDS} nodes; a count
 * line says what was elided (no silent truncation).
 */
final class CatalogPane {

    static final int MAX_CARDS = 60;
    private static final int PRESEED_LANGUAGES = 8;

    /** What the library already knows about a variant. */
    /**
     * What the app knows about one entry right now.
     *
     * <p>The kit is explicit that "the catalog and the library never tell different stories about
     * the same archive", so a card shows the same live state a Library row would — including the
     * amber verifying pass and a quarantined file's repair — rather than a flat installed/not.
     *
     * @param snapshot the live download, or null when nothing is in flight for this entry
     */
    record CardState(Installed installed, ProgressSnapshot snapshot, Runnable onPause) {
        static CardState of(Installed installed) {
            return new CardState(installed, null, null);
        }

        boolean inFlight() {
            return snapshot != null && !snapshot.state().isTerminal();
        }
    }

    enum Installed {
        NO,
        YES,
        /**
         * On disk, but this catalog entry is a newer build of it.
         *
         * <p>Its own state rather than a flag on YES, because it changes what the card's one
         * button does — "Open" becomes "Update" — and a green "In library" tick beside an archive
         * a year out of date is the catalog and the library telling different stories about the
         * same file, which is the thing the shared state vocabulary exists to prevent.
         */
        OUTDATED
    }

    private final CatalogCache cache;
    private final IconCache icons;
    private final BiConsumer<ZimEntry, String> onDownload; // entry + display title
    private final Function<ZimEntry, CardState> installedState;
    /** Per-entry card controls, so a 4 Hz tick updates them in place instead of rebuilding. */
    private final java.util.Map<String, CardControls> liveCards = new java.util.HashMap<>();

    private record CardControls(ZimEntry entry, HBox actions, Button action) {}

    private final javafx.animation.Timeline sampler = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(250), e -> refreshCardStates()));
    private final Function<ZimEntry, Path> installedFile;
    private final java.util.function.Consumer<Path> onOpenArchive;
    private final java.util.function.Consumer<String> onStatus;
    private final java.util.function.LongSupplier freeDiskBytes;

    private final BorderPane root = new BorderPane();
    private final TextField search = new TextField();
    private final Label freshness = new Label();
    private final Button refreshButton = new Button("↻ Refresh");
    private final VBox facetRail = new VBox(2);
    private final FlowPane cards = new FlowPane(12, 12);
    private final Label resultLine = new Label();
    private final Button prevPage = new Button("← Previous");
    private final Button nextPage = new Button("Next →");
    private final HBox pager = new HBox(8);
    private int page;

    private List<CatalogGroups.TitleGroup> allGroups = List.of();
    private final Set<String> selectedLanguages = new LinkedHashSet<>();
    private String selectedCategory = "";
    private boolean refreshing;

    CatalogPane(
            CatalogCache cache,
            IconCache icons,
            BiConsumer<ZimEntry, String> onDownload,
            Function<ZimEntry, CardState> installedState,
            Function<ZimEntry, Path> installedFile,
            java.util.function.Consumer<Path> onOpenArchive,
            java.util.function.Consumer<String> onStatus,
            java.util.function.LongSupplier freeDiskBytes) {
        this.cache = cache;
        this.icons = icons;
        this.onDownload = onDownload;
        this.installedState = installedState;
        this.installedFile = installedFile;
        this.onOpenArchive = onOpenArchive;
        this.onStatus = onStatus;
        this.freeDiskBytes = freeDiskBytes;
        build();
    }

    /** The rendered card nodes. A test must not walk the scene: a ScrollPane's content is not
     * reachable through its children until it has been skinned, which silently turns an
     * absence-assertion into a vacuous pass. */
    void goToPageForTest(int target) {
        goToPage(target);
    }

    int pageForTest() {
        return page;
    }

    /** Every label rendered across the visible cards, for asserting what a card actually says. */
    String cardTextForTest() {
        StringBuilder sb = new StringBuilder();
        collectText(cards, sb);
        return sb.toString();
    }

    private static void collectText(javafx.scene.Node node, StringBuilder sb) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectText(child, sb));
        }
    }

    java.util.List<javafx.scene.Node> cardNodesForTest() {
        return java.util.List.copyOf(cards.getChildren());
    }

    Region node() {
        return root;
    }

    /** Loads the cache (off-thread) and auto-refreshes when it is older than the spec's 7 days. */
    /** Starts the 4 Hz state tick; stops itself once nothing is in flight. */
    private void startSampler() {
        idleTicks = 0;
        sampler.setCycleCount(javafx.animation.Animation.INDEFINITE);
        if (sampler.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            sampler.play();
        }
    }

    void deactivate() {
        sampler.stop();
    }

    /**
     * Repaints the cards now. Called when an archive is admitted to the library, so a card flips
     * to "In library" on the event itself rather than waiting for a sampler that may already have
     * stopped.
     */
    void refreshStates() {
        refreshCardStates();
    }

    boolean samplerRunningForTest() {
        return sampler.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    void activate() {
        startSampler();
        reloadFromCache();
        if (cache.isOlderThan(CatalogCache.AUTO_REFRESH_AGE, System.currentTimeMillis()) && !refreshing) {
            refresh(true);
        }
    }

    void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    void refreshCommand() {
        refresh(false);
    }

    // ------------------------------------------------------- package-visible test seams

    int renderedCardsForTest() {
        return cards.getChildren().size();
    }

    void setSearchTextForTest(String text) {
        search.setText(text);
    }

    void setLanguagesForTest(Set<String> languages) {
        selectedLanguages.clear();
        selectedLanguages.addAll(languages);
        renderFromFirstPage();
    }

    String freshnessTextForTest() {
        return freshness.getText();
    }

    // ---------------------------------------------------------------- build

    private void build() {
        search.setPromptText("Search the catalog — title, name or description");
        search.textProperty().addListener((obs, old, text) -> renderFromFirstPage());

        refreshButton.setOnAction(e -> refresh(false));
        HBox bar = new HBox(12, search, freshness, refreshButton);
        HBox.setHgrow(search, Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));

        facetRail.setPadding(new Insets(8));
        facetRail.setPrefWidth(190);
        ScrollPane facetScroll = new ScrollPane(facetRail);
        facetScroll.setFitToWidth(true);
        facetScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        cards.setPadding(new Insets(12));
        cards.setPrefWrapLength(700);
        prevPage.setOnAction(e -> goToPage(page - 1));
        nextPage.setOnAction(e -> goToPage(page + 1));
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(0, 14, 12, 14));
        pager.getChildren().addAll(prevPage, nextPage, resultLine);
        VBox center = new VBox(cards, pager);
        resultLine.getStyleClass().add("card-sub");
        ScrollPane cardScroll = new ScrollPane(center);
        cardScroll.setFitToWidth(true);

        root.setTop(bar);
        root.setLeft(facetScroll);
        root.setCenter(cardScroll);
    }

    // ---------------------------------------------------------------- data

    private void reloadFromCache() {
        Thread loader = new Thread(
                () -> {
                    List<CatalogGroups.TitleGroup> groups = CatalogGroups.group(cache.entries());
                    Platform.runLater(() -> {
                        allGroups = groups;
                        preseedLanguages(groups);
                        render();
                    });
                },
                "store-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** First activation: check the system language plus English — 182 facets is a wall of noise. */
    private void preseedLanguages(List<CatalogGroups.TitleGroup> groups) {
        if (!selectedLanguages.isEmpty() || groups.isEmpty()) {
            return;
        }
        String system = Locale.getDefault().getISO3Language();
        selectedLanguages.add("eng");
        if (!system.isBlank()) {
            selectedLanguages.add(system);
        }
    }

    private void refresh(boolean silent) {
        refreshing = true;
        if (!silent) {
            freshness.setText("Refreshing…");
        }
        cache.refresh(outcome -> Platform.runLater(() -> {
            refreshing = false;
            if (!outcome.ok()) {
                freshness.setText("Offline — showing cached catalog");
                if (!silent) {
                    onStatus.accept(outcome.error());
                }
                return;
            }
            if (outcome.updated()) {
                reloadFromCache();
            } else {
                render();
            }
            if (!silent) {
                onStatus.accept(outcome.updated() ? "Catalog updated" : "Catalog already up to date");
            }
        }));
    }

    // ---------------------------------------------------------------- render

    /** Any change to what is being filtered starts over at page one. */
    private void renderFromFirstPage() {
        page = 0;
        render();
    }

    private void render() {
        CatalogFilter.Result result =
                CatalogFilter.apply(allGroups, search.getText(), selectedLanguages, selectedCategory);
        renderFacets(result);
        renderCards(result.groups());
        renderFreshness();
    }

    private void renderFreshness() {
        long fetched = cache.fetchedAtMillis();
        if (fetched == 0) {
            freshness.setText(allGroups.isEmpty() ? "No catalog yet — refresh to fetch it" : "");
            return;
        }
        long days = (System.currentTimeMillis() - fetched) / 86_400_000L;
        String age = days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
        boolean stale = cache.isOlderThan(CatalogCache.STALE_AGE, System.currentTimeMillis());
        freshness.setText("Catalog updated " + age);
        // Stale is the kit's amber, and only past the two-week mark.
        freshness.getStyleClass().removeAll("pill-amber", "pill-neutral", "pill");
        freshness.getStyleClass().addAll("pill", stale ? "pill-amber" : "pill-neutral");
    }

    private void renderFacets(CatalogFilter.Result result) {
        facetRail.getChildren().clear();
        facetRail.getChildren().add(facetTitle("Language"));
        result.languages().stream().limit(PRESEED_LANGUAGES).forEach(facet -> {
            CheckBox box =
                    new CheckBox(com.insula.catalog.LanguageNames.one(facet.value()) + "  (" + facet.count() + ")");
            box.setSelected(selectedLanguages.contains(facet.value()));
            box.setDisable(facet.count() == 0);
            box.selectedProperty().addListener((obs, old, on) -> {
                if (on) {
                    selectedLanguages.add(facet.value());
                } else {
                    selectedLanguages.remove(facet.value());
                }
                renderFromFirstPage();
            });
            facetRail.getChildren().add(box);
        });

        facetRail.getChildren().add(facetTitle("Category"));
        ToggleGroup categories = new ToggleGroup();
        addCategoryToggle(categories, "All", "");
        result.categories()
                .forEach(facet ->
                        addCategoryToggle(categories, facet.value() + "  (" + facet.count() + ")", facet.value()));
    }

    private void addCategoryToggle(ToggleGroup group, String label, String value) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setSelected(value.equals(selectedCategory));
        button.setOnAction(e -> {
            selectedCategory = button.isSelected() ? value : "";
            renderFromFirstPage();
        });
        facetRail.getChildren().add(button);
    }

    private static Label facetTitle(String text) {
        Label label = new Label(text.toUpperCase(Locale.ROOT));
        label.getStyleClass().add("hsec-title");
        label.setPadding(new Insets(10, 0, 2, 2));
        return label;
    }

    /**
     * Jumps to a page. Clamped, so the Next button on the last page is inert rather than blanking
     * the grid.
     */
    private void goToPage(int target) {
        int total = CatalogFilter.apply(allGroups, search.getText(), selectedLanguages, selectedCategory)
                .groups()
                .size();
        int clamped = Paging.clamp(target, total, Paging.PAGE_SIZE);
        if (clamped != page) {
            page = clamped;
            render();
            cards.requestFocus();
        }
    }

    private void renderCards(List<CatalogGroups.TitleGroup> groups) {
        liveCards.clear();
        cards.getChildren().clear();
        // Clamped here as well as on the buttons: a filter can shrink the result under a page
        // the user is already on, and an empty grid over a non-empty result reads as
        // "nothing matches" when plenty does.
        page = Paging.clamp(page, groups.size(), Paging.PAGE_SIZE);
        Paging.slice(groups, page, Paging.PAGE_SIZE)
                .forEach(group -> cards.getChildren().add(card(group)));
        startSampler();

        int pages = Paging.pageCount(groups.size(), Paging.PAGE_SIZE);
        boolean many = pages > 1;
        prevPage.setVisible(many);
        prevPage.setManaged(many);
        nextPage.setVisible(many);
        nextPage.setManaged(many);
        prevPage.setDisable(page == 0);
        nextPage.setDisable(page >= pages - 1);
        resultLine.setText(
                groups.isEmpty() && !allGroups.isEmpty()
                        ? "Nothing matches"
                        : Paging.label(page, groups.size(), Paging.PAGE_SIZE));
    }

    private Region card(CatalogGroups.TitleGroup group) {
        StackPane icon = monogram(group.title());
        String iconUrl = cache.resolveHref(group.illustrationHref());
        icons.icon(iconUrl, image -> {
            ImageView view = new ImageView(image);
            view.setFitWidth(40);
            view.setFitHeight(40);
            icon.getChildren().setAll(view);
        });

        Label title = new Label(group.title());
        title.getStyleClass().add("card-title");
        Label sub = new Label(com.insula.catalog.LanguageNames.display(group.language()));
        sub.getStyleClass().add("card-faint");
        VBox heading = new VBox(1, title, sub);
        HBox head = new HBox(10, icon, heading);
        head.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(group.summary());
        description.setWrapText(true);
        description.setMaxHeight(38);
        description.getStyleClass().add("card-sub");

        Label meta = new Label(metaLine(group));
        meta.getStyleClass().add("card-faint");

        // Flavour choice, sizes as labels; pre-select the largest that fits the disk.
        CatalogGroups.Variant preselected = group.defaultVariant(freeDiskBytes.getAsLong());
        ToggleGroup flavours = new ToggleGroup();
        HBox seg = new HBox(4);
        seg.getStyleClass().add("surfaces");
        Button action = new Button();
        for (CatalogGroups.Variant variant : group.variants()) {
            ToggleButton flavourButton = new ToggleButton(variant.flavourLabel() + " · "
                    + Formats.bytes(variant.entry().sizeBytes()));
            flavourButton.setToggleGroup(flavours);
            flavourButton.setUserData(variant);
            flavourButton.setTooltip(new Tooltip(flavourTip(variant.flavourLabel())));
            flavourButton.setSelected(variant == preselected);
            seg.getChildren().add(flavourButton);
        }
        flavours.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null && old != null) {
                old.setSelected(true); // one flavour is always chosen
            } else {
                applyCardState(
                        liveCards.get(selectedVariant(flavours, group).entry().id()));
            }
        });

        action.setOnAction(e -> {
            CatalogGroups.Variant variant = selectedVariant(flavours, group);
            if (installedState.apply(variant.entry()).installed() == Installed.YES) {
                Path file = installedFile.apply(variant.entry());
                if (file != null) {
                    onOpenArchive.accept(file);
                }
            } else {
                onDownload.accept(variant.entry(), group.title());
                // The sampler stops itself whenever nothing is in flight — which is the normal
                // state of the Catalog — so a download that does not wake it leaves its own card
                // frozen on "Download" for the entire transfer.
                startSampler();
                refreshCardStates();
            }
        });

        HBox actions = new HBox(8, action);
        actions.setAlignment(Pos.CENTER_LEFT);
        liveCards.put(preselected.entry().id(), new CardControls(preselected.entry(), actions, action));
        flavours.selectedToggleProperty().addListener((obs, old, toggle) -> {
            CatalogGroups.Variant chosen = selectedVariant(flavours, group);
            liveCards.values().removeIf(c -> c.actions() == actions);
            liveCards.put(chosen.entry().id(), new CardControls(chosen.entry(), actions, action));
            applyCardState(liveCards.get(chosen.entry().id()));
        });
        applyCardState(liveCards.get(preselected.entry().id()));

        VBox card = new VBox(7, head, description, meta, seg, actions);
        card.setPadding(new Insets(12));
        card.setPrefWidth(330);
        card.getStyleClass().add("rowcard");
        return card;
    }

    /** Re-reads every visible card's state; cheap because it only touches the pill and button. */
    /**
     * Repaints every visible card, and decides whether to keep sampling.
     *
     * <p>It keeps going for a short while after the last download goes terminal. A finished
     * transfer is not yet an installed archive: verification and admission to the library happen
     * afterwards, on other threads, and stopping the instant the progress bar ends would freeze
     * the card on "Downloading · 100%" until something else happened to re-render it.
     */
    private void refreshCardStates() {
        boolean anyLive = false;
        for (CardControls controls : liveCards.values()) {
            anyLive |= applyCardState(controls);
        }
        if (anyLive) {
            idleTicks = 0;
            return;
        }
        if (++idleTicks >= IDLE_TICKS_BEFORE_STOP) {
            sampler.stop();
        }
    }

    /** ~3 s of sampling past the last live download, which is ample for verify-and-admit. */
    private static final int IDLE_TICKS_BEFORE_STOP = 12;

    private int idleTicks;

    /** Returns whether this entry is still moving, so the sampler can stop when nothing is. */
    private boolean applyCardState(CardControls controls) {
        if (controls == null) {
            return false;
        }
        CardState state = installedState.apply(controls.entry());
        controls.actions().getChildren().retainAll(java.util.List.of(controls.action()));
        updateAction(controls.action(), controls.entry(), state);

        if (state.snapshot() != null) {
            controls.actions().getChildren().add(Pills.forDownload(state.snapshot()));
            if (state.inFlight() && state.onPause() != null) {
                Button pause = new Button("Pause");
                pause.setOnAction(e -> state.onPause().run());
                controls.actions().getChildren().add(pause);
            }
        } else if (state.installed() == Installed.OUTDATED) {
            controls.actions()
                    .getChildren()
                    .add(Pills.update(
                            buildOf(controls.entry()),
                            Formats.bytes(controls.entry().sizeBytes())));
        } else if (state.installed() == Installed.YES) {
            controls.actions().getChildren().add(Pills.verified());
        }
        return state.inFlight();
    }

    private CatalogGroups.Variant selectedVariant(ToggleGroup flavours, CatalogGroups.TitleGroup group) {
        Toggle toggle = flavours.getSelectedToggle();
        return toggle != null
                ? (CatalogGroups.Variant) toggle.getUserData()
                : group.variants().getFirst();
    }

    /** The size lives on the button: nobody should start a 110 GB transfer by accident. */
    private void updateAction(Button action, ZimEntry entry, CardState state) {
        boolean busy = state.inFlight();
        action.setDisable(busy);
        if (state.installed() == Installed.YES) {
            action.setText("Open");
        } else if (state.installed() == Installed.OUTDATED) {
            // The size stays on the button: an update is a whole new archive, not a patch.
            action.setText("Update · " + Formats.bytes(entry.sizeBytes()));
        } else {
            action.setText("Download · " + Formats.bytes(entry.sizeBytes()));
        }
    }

    /** The build stamp a card's Update pill names, e.g. {@code 2026-08}. */
    private static String buildOf(ZimEntry entry) {
        String date = com.insula.catalog.UpdateCheck.buildDateOf(entry.fileName());
        return date.isBlank() ? "newer" : date;
    }

    private static String metaLine(CatalogGroups.TitleGroup group) {
        StringBuilder sb = new StringBuilder();
        if (group.articleCount() > 0) {
            sb.append(String.format(Locale.ROOT, "%,d", group.articleCount())).append(" articles");
        }
        if (!group.newestUpdated().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("updated ").append(group.newestUpdated());
        }
        return sb.toString();
    }

    private static String flavourTip(String label) {
        return switch (label) {
            case "mini" -> "Introductions only — the smallest edition";
            case "nopic" -> "Full text, no images";
            case "maxi" -> "Everything, including images";
            default -> "The complete edition";
        };
    }

    /** Coloured monogram fallback until (or unless) the real icon arrives. */
    private static StackPane monogram(String title) {
        String letter = title.isBlank() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        Rectangle bg = new Rectangle(40, 40);
        bg.setArcWidth(12);
        bg.setArcHeight(12);
        bg.setFill(Color.hsb(Math.floorMod(title.hashCode(), 360), 0.35, 0.55));
        Text text = new Text(letter);
        text.setFont(Font.font(18));
        text.setFill(Color.WHITE);
        StackPane pane = new StackPane(bg, text);
        pane.setMinSize(40, 40);
        pane.setMaxSize(40, 40);
        return pane;
    }
}
