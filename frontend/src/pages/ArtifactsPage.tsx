import { useEffect, useState } from 'react';
import { parsedDataApi, ParsedData } from '../api/parsedData';
import ScreenshotViewer from '../components/ScreenshotViewer';
import './ArtifactsPage.css';

export default function ArtifactsPage() {
  const [data, setData] = useState<ParsedData[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    try {
      const result = await parsedDataApi.getAll({ limit: 100 });
      setData(result.data);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка загрузки артефактов');
    } finally {
      setLoading(false);
    }
  };

  const handleExportCSV = async () => {
    try {
      const blob = await parsedDataApi.exportCSV();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `parsed-data-${new Date().toISOString()}.csv`;
      a.click();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      alert('Ошибка экспорта CSV');
    }
  };

  const handleExportJSON = async () => {
    try {
      const data = await parsedDataApi.exportJSON();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `parsed-data-${new Date().toISOString()}.json`;
      a.click();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      alert('Ошибка экспорта JSON');
    }
  };

  return (
    <div className="artifacts-page page-shell">
      <section className="page-hero">
        <div className="hero-text">
          <p className="hero-eyebrow">Artifacts</p>
          <h2>Цифровые доказательства</h2>
          <p className="hero-description">
            Снимки экранов и ссылки, полученные в ходе задач. Храним до 100 элементов, экспортируем
            в любой момент. Бело-чёрный интерфейс повторяет эстетику корпоративного сайта.
          </p>
        </div>
        <div className="hero-actions">
          <button onClick={handleExportCSV} className="tf-btn primary">
            Экспорт CSV
          </button>
          <button onClick={handleExportJSON} className="tf-btn ghost">
            Экспорт JSON
          </button>
        </div>
      </section>

      <section className="page-section">
        <div className="section-heading">
          <h3>Артефакты</h3>
          <span className="section-meta">{data.length} записей</span>
        </div>
        {loading && <div className="loading">Загрузка артефактов...</div>}
        {error && <div className="error">Ошибка: {error}</div>}
        {!loading && !error && <ScreenshotViewer data={data} />}
      </section>
    </div>
  );
}

