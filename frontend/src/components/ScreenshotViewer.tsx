import { ParsedData, parsedDataApi } from '../api/parsedData';
import { API_BASE_URL } from '../api/client';
import './ScreenshotViewer.css';

interface ScreenshotViewerProps {
  data: ParsedData[];
  onArtifactDeleted?: () => void;
}

export default function ScreenshotViewer({ data, onArtifactDeleted }: ScreenshotViewerProps) {
  const handleDelete = async (e: React.MouseEvent, id: string) => {
    e.preventDefault();
    e.stopPropagation();
    
    if (!confirm('Удалить артефакт?')) return;
    
    try {
      await parsedDataApi.delete(id);
      onArtifactDeleted?.();
    } catch (err) {
      alert('Ошибка удаления артефакта');
    }
  };

  if (data.length === 0) {
    return <div className="empty-state">Нет артефактов</div>;
  }

  return (
    <div className="screenshot-viewer">
      <div className="artifacts-grid">
        {data.map((item) => (
          <div key={item.id} className="artifact-card">
            <button 
              className="delete-btn artifact-delete-btn" 
              onClick={(e) => handleDelete(e, item.id)}
              title="Удалить артефакт"
            >
              ✕
            </button>
            <div className="artifact-info">
              <div className="artifact-url">{item.url}</div>
              {item.adDomain && (
                <div className="artifact-domain">Домен: {item.adDomain}</div>
              )}
              {item.task && (
                <div className="artifact-task">Задача: {item.task.name}</div>
              )}
              {item.parsedAt && (
                <div className="artifact-date">
                  {new Date(item.parsedAt).toLocaleString('ru-RU')}
                </div>
              )}
            </div>
            {item.screenshotPath && (
              <div className="screenshot-container">
                <img
                  src={`${API_BASE_URL}/artifacts/${item.screenshotPath}`}
                  alt="Screenshot"
                  className="screenshot-image"
                  onError={(e) => {
                    (e.target as HTMLImageElement).style.display = 'none';
                  }}
                />
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

