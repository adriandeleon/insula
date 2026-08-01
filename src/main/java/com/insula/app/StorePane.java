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
import com.insula.catalog.CatalogGroups;
import com.insula.catalog.StoreFilter;
import com.insula.catalog.ZimEntry;

/**
 * The Store: a faceted, card-based browser over the cached catalog.
 *
 * <p>Everything here runs against {@link CatalogCache} — filtering and search are local and
 * instant, and the network is touched only by the Refresh affordance (or the silent auto-refresh
 * when the cache is a week old). Offline, everything works except Download.
 *
 * <p>One card per title with the flavour as a segmented choice labelled by size — the size
 * <em>is</em> the meaning of the choice. Rendering is capped at {@link #MAX_CARDS} nodes; a count
 * line says what was elided (no silent truncation).
 */
final class StorePane {

    static final int MAX_CARDS = 60;
    private static final int PRESEED_LANGUAGES = 8;

    /** What the library already knows about a variant. */
    enum Installed {
        NO,
        YES
    }

    private final CatalogCache cache;
    private final IconCache icons;
    private final BiConsumer<ZimEntry, String> onDownload; // entry + display title
    private final Function<ZimEntry, Installed> installedState;
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

    private List<CatalogGroups.TitleGroup> allGroups = List.of();
    private final Set<String> selectedLanguages = new LinkedHashSet<>();
    private String selectedCategory = "";
    private boolean refreshing;

    StorePane(
            CatalogCache cache,
            IconCache icons,
            BiConsumer<ZimEntry, String> onDownload,
            Function<ZimEntry, Installed> installedState,
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

    Region node() {
        return root;
    }

    /** Loads the cache (off-thread) and auto-refreshes when it is older than the spec's 7 days. */
    void activate() {
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
        render();
    }

    String freshnessTextForTest() {
        return freshness.getText();
    }

    // ---------------------------------------------------------------- build

    private void build() {
        search.setPromptText("Search the catalog — title, name or description");
        search.textProperty().addListener((obs, old, text) -> render());

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
        VBox center = new VBox(cards, resultLine);
        resultLine.setPadding(new Insets(0, 14, 10, 14));
        resultLine.setStyle("-fx-opacity: 0.65;");
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

    private void render() {
        StoreFilter.Result result = StoreFilter.apply(allGroups, search.getText(), selectedLanguages, selectedCategory);
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
        freshness.setStyle(stale ? "-fx-text-fill: #a16207;" : "-fx-opacity: 0.7;");
    }

    private void renderFacets(StoreFilter.Result result) {
        facetRail.getChildren().clear();
        facetRail.getChildren().add(facetTitle("Language"));
        result.languages().stream().limit(PRESEED_LANGUAGES).forEach(facet -> {
            CheckBox box = new CheckBox(facet.value() + "  (" + facet.count() + ")");
            box.setSelected(selectedLanguages.contains(facet.value()));
            box.setDisable(facet.count() == 0);
            box.selectedProperty().addListener((obs, old, on) -> {
                if (on) {
                    selectedLanguages.add(facet.value());
                } else {
                    selectedLanguages.remove(facet.value());
                }
                render();
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
            render();
        });
        facetRail.getChildren().add(button);
    }

    private static Label facetTitle(String text) {
        Label label = new Label(text.toUpperCase(Locale.ROOT));
        label.setStyle("-fx-font-size: 0.75em; -fx-opacity: 0.6;");
        label.setPadding(new Insets(10, 0, 2, 2));
        return label;
    }

    private void renderCards(List<CatalogGroups.TitleGroup> groups) {
        cards.getChildren().clear();
        groups.stream().limit(MAX_CARDS).forEach(group -> cards.getChildren().add(card(group)));
        resultLine.setText(
                groups.size() > MAX_CARDS
                        ? "Showing " + MAX_CARDS + " of " + groups.size()
                                + " — narrow the search or facets to see the rest"
                        : groups.isEmpty() && !allGroups.isEmpty() ? "Nothing matches" : "");
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
        title.setStyle("-fx-font-weight: bold;");
        Label sub = new Label(group.language());
        sub.setStyle("-fx-opacity: 0.6; -fx-font-size: 0.85em;");
        VBox heading = new VBox(1, title, sub);
        HBox head = new HBox(10, icon, heading);
        head.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(group.summary());
        description.setWrapText(true);
        description.setMaxHeight(38);
        description.setStyle("-fx-opacity: 0.75; -fx-font-size: 0.9em;");

        Label meta = new Label(metaLine(group));
        meta.setStyle("-fx-opacity: 0.55; -fx-font-size: 0.8em;");

        // Flavour choice, sizes as labels; pre-select the largest that fits the disk.
        CatalogGroups.Variant preselected = group.defaultVariant(freeDiskBytes.getAsLong());
        ToggleGroup flavours = new ToggleGroup();
        HBox seg = new HBox(4);
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
                updateAction(action, selectedVariant(flavours, group));
            }
        });

        updateAction(action, preselected);
        action.setOnAction(e -> {
            CatalogGroups.Variant variant = selectedVariant(flavours, group);
            if (installedState.apply(variant.entry()) == Installed.YES) {
                Path file = installedFile.apply(variant.entry());
                if (file != null) {
                    onOpenArchive.accept(file);
                }
            } else {
                onDownload.accept(variant.entry(), group.title());
            }
        });

        HBox actions = new HBox(8, action);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(7, head, description, meta, seg, actions);
        card.setPadding(new Insets(12));
        card.setPrefWidth(330);
        card.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;");
        return card;
    }

    private CatalogGroups.Variant selectedVariant(ToggleGroup flavours, CatalogGroups.TitleGroup group) {
        Toggle toggle = flavours.getSelectedToggle();
        return toggle != null
                ? (CatalogGroups.Variant) toggle.getUserData()
                : group.variants().getFirst();
    }

    private void updateAction(Button action, CatalogGroups.Variant variant) {
        if (installedState.apply(variant.entry()) == Installed.YES) {
            action.setText("Open");
        } else {
            action.setText("Download · " + Formats.bytes(variant.entry().sizeBytes()));
        }
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
