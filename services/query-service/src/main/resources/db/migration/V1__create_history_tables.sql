CREATE TABLE song_completion_stats (
    song_id         TEXT             NOT NULL,
    window_start    BIGINT           NOT NULL,
    window_end      BIGINT           NOT NULL,
    starts          BIGINT           NOT NULL,
    completes       BIGINT           NOT NULL,
    completion_rate DOUBLE PRECISION NOT NULL,
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (song_id, window_start)
);

CREATE INDEX idx_song_completion_stats_window_start ON song_completion_stats (window_start);

CREATE TABLE song_hourly_counts (
    song_id      TEXT        NOT NULL,
    window_start BIGINT      NOT NULL,
    window_end   BIGINT      NOT NULL,
    play_count   BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (song_id, window_start)
);

CREATE INDEX idx_song_hourly_counts_window_start ON song_hourly_counts (window_start);
