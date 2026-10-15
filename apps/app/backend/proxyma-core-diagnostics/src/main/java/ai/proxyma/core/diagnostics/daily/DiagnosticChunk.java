package ai.proxyma.core.diagnostics.daily;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One flush of the in-memory diagnostics aggregates for one UTC day, waiting to be sent.
 *
 * <p>APPEND-ONLY. Each flush writes new rows rather than merging into the day's existing one, and
 * the report builder merges them when it reads. That keeps a flush a plain INSERT - no read-modify-
 * write to race against a second flush or a send - and a day is at most 144 rows (one per ten
 * minutes), which is nothing to merge.
 *
 * <p>Rows exist only while "Send diagnostics to Proxyma" is on: nothing is written otherwise, and
 * turning it off deletes every row. A row is deleted as soon as the report carrying it is accepted,
 * and anything older than 30 days is deleted unsent.
 *
 * <p>{@code payload} is a serialized {@code DiagnosticsDay} - counts, durations and code-defined
 * tokens only; see that class for what can and cannot be in it.
 */
@Entity
@Table(name = "DIAGNOSTIC_DAILY", indexes = @Index(name = "idx_diag_daily_day", columnList = "DIAG_DAY"))
public class DiagnosticChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DIAG_DAY", nullable = false)
    private LocalDate day;

    @Column(name = "APP_VERSION", length = 32)
    private String appVersion;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "PAYLOAD", nullable = false, columnDefinition = "TEXT")
    private String payload;

    public Long getId() { return id; }

    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
