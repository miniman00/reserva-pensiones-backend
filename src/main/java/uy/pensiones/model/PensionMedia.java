package uy.pensiones.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_media")
public class PensionMedia {

    public enum Kind { IMAGE, VIDEO, YOUTUBE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Pension pension;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    // Para archivos subidos
    @Column(length = 255)
    private String filename;     // nombre físico

    // URL pública (para YOUTUBE guardamos el link o el embed)
    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 120)
    private String mimeType;

    private Long sizeBytes;

    private Integer width;       // opcional para imagen
    private Integer height;      // opcional para imagen
    private Integer durationSec; // opcional para video

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private boolean cover = false;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters/setters
    public Long getId() { return id; }
    public Pension getPension() { return pension; }
    public void setPension(Pension pension) { this.pension = pension; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public boolean isCover() { return cover; }
    public void setCover(boolean cover) { this.cover = cover; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
