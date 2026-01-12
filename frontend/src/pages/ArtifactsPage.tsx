import { useEffect, useState } from 'react';
import { artifactsApi, Artifact, ArtifactsStats } from '../api/artifacts';
import './ArtifactsPage.css';

export default function ArtifactsPage() {
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [stats, setStats] = useState<ArtifactsStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedArtifact, setSelectedArtifact] = useState<Artifact | null>(null);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    try {
      setLoading(true);
      const [artifactsResult, statsResult] = await Promise.all([
        artifactsApi.getAll({ limit: 100 }),
        artifactsApi.getStats(),
      ]);
      setArtifacts(artifactsResult.artifacts);
      setStats(statsResult.stats);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка загрузки артефактов');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (artifact: Artifact) => {
    if (!confirm(`Удалить артефакт ${artifact.path}?`)) return;
    try {
      await artifactsApi.delete(artifact.path);
      await loadData();
    } catch (err: any) {
      alert('Ошибка удаления: ' + (err.response?.data?.error?.message || err.message));
    }
  };

  const formatSize = (bytes: number | null) => {
    if (!bytes) return 'N/A';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString('ru-RU');
  };

  return (
    <div className="artifacts-page page-shell">
      <section className="page-hero">
        <div className="hero-text">
          <p className="hero-eyebrow">Artifacts</p>
          <h2>Скриншоты и артефакты</h2>
          <p className="hero-description">
            Снимки экранов, полученные в ходе выполнения задач. Хранятся в MinIO/S3 хранилище.
          </p>
        </div>
        <div className="hero-actions">
          <button onClick={loadData} className="tf-btn ghost">
            🔄 Обновить
          </button>
        </div>
      </section>

      {stats && (
        <section className="stats-section">
          <div className="stats-grid">
            <div className="stat-card">
              <div className="stat-value">{stats.total}</div>
              <div className="stat-label">Всего артефактов</div>
            </div>
            <div className="stat-card">
              <div className="stat-value">{stats.recent24h}</div>
              <div className="stat-label">За 24 часа</div>
            </div>
            <div className="stat-card">
              <div className="stat-value">{Object.keys(stats.byDevice).length}</div>
              <div className="stat-label">Устройств</div>
            </div>
            <div className="stat-card">
              <div className="stat-value">{formatSize(stats.totalSizeBytes)}</div>
              <div className="stat-label">Общий размер</div>
            </div>
          </div>
        </section>
      )}

      <section className="page-section">
        <div className="section-heading">
          <h3>Список артефактов</h3>
          <span className="section-meta">{artifacts.length} записей</span>
        </div>
        
        {loading && <div className="loading">Загрузка артефактов...</div>}
        {error && <div className="error">Ошибка: {error}</div>}
        
        {!loading && !error && artifacts.length === 0 && (
          <div className="empty-state">
            <p>Нет артефактов</p>
            <p className="hint">Скриншоты появятся здесь после выполнения задач с действием take_screenshot</p>
          </div>
        )}

        {!loading && !error && artifacts.length > 0 && (
          <div className="artifacts-grid">
            {artifacts.map((artifact) => (
              <div key={artifact.id} className="artifact-card">
                <div className="artifact-preview" onClick={() => setSelectedArtifact(artifact)}>
                  {artifact.imageUrl ? (
                    <img src={artifact.imageUrl} alt="Screenshot" loading="lazy" />
                  ) : (
                    <div className="no-preview">Нет превью</div>
                  )}
                </div>
                <div className="artifact-info">
                  <div className="artifact-type">{artifact.type}</div>
                  <div className="artifact-size">{formatSize(artifact.size)}</div>
                  <div className="artifact-date">{formatDate(artifact.capturedAt)}</div>
                  {artifact.url && (
                    <div className="artifact-page-url" title={artifact.url}>
                      <a href={artifact.url} target="_blank" rel="noopener noreferrer" onClick={(e) => e.stopPropagation()}>
                        🌐 {artifact.url.length > 50 ? artifact.url.substring(0, 50) + '...' : artifact.url}
                      </a>
                    </div>
                  )}
                  <div className="artifact-task">Task: {artifact.taskId.slice(0, 8)}...</div>
                </div>
                <div className="artifact-actions">
                  {artifact.url && (
                    <a href={artifact.url} target="_blank" rel="noopener noreferrer" className="btn-view">
                      👁️ Открыть страницу
                    </a>
                  )}
                  {artifact.imageUrl && (
                    <a href={artifact.imageUrl} target="_blank" rel="noopener noreferrer" className="btn-view">
                      🖼️ Открыть скриншот
                    </a>
                  )}
                  <button onClick={() => handleDelete(artifact)} className="btn-delete">
                    🗑️
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {selectedArtifact && (
        <div className="artifact-modal" onClick={() => setSelectedArtifact(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <button className="modal-close" onClick={() => setSelectedArtifact(null)}>×</button>
            {selectedArtifact.imageUrl && (
              <img src={selectedArtifact.imageUrl} alt="Screenshot" />
            )}
            <div className="modal-info">
              <p><strong>ID:</strong> {selectedArtifact.id}</p>
              <p><strong>Тип:</strong> {selectedArtifact.type}</p>
              <p><strong>Размер:</strong> {formatSize(selectedArtifact.size)}</p>
              <p><strong>Дата:</strong> {formatDate(selectedArtifact.capturedAt)}</p>
              {selectedArtifact.url && (
                <p><strong>URL страницы:</strong> <a href={selectedArtifact.url} target="_blank" rel="noopener noreferrer">{selectedArtifact.url}</a></p>
              )}
              <p><strong>Task ID:</strong> {selectedArtifact.taskId}</p>
              <p><strong>Device ID:</strong> {selectedArtifact.deviceId}</p>
              <p><strong>Путь:</strong> {selectedArtifact.path}</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
