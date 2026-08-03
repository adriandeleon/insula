package com.insula.download;

/**
 * What a BitTorrent transfer is doing beyond its progress bar: who it is talking to, and what it
 * has given back.
 *
 * <p>Carried separately from {@link ProgressSnapshot}'s common fields because it is genuinely
 * transport-specific — an HTTP download has mirrors, not peers, and nothing to upload. A null
 * instance means "this is not a torrent", which is the honest way to say it rather than a row of
 * zeroes that reads like a stalled swarm.
 *
 * @param peers connected peers, including seeds
 * @param seeds peers that already have the whole file
 * @param uploadRate bytes per second currently going out
 * @param uploaded bytes sent to other peers over this session
 * @param webSeeds HTTP mirrors merged into the swarm from the {@code .meta4}
 * @param seeding true once the file is complete and the session is only giving back
 */
public record SwarmStats(int peers, int seeds, long uploadRate, long uploaded, int webSeeds, boolean seeding) {

    /** The ratio given back, or 0 when nothing has been downloaded yet to compare against. */
    public double ratio(long downloaded) {
        return downloaded <= 0 ? 0 : (double) uploaded / downloaded;
    }

    /** Leechers is what is left once the seeds are taken out — never negative on odd input. */
    public int leechers() {
        return Math.max(0, peers - seeds);
    }
}
