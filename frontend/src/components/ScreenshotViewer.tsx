import { ParsedData } from '../api/parsedData';
import './ScreenshotViewer.css';

interface ScreenshotViewerProps {
  data: ParsedData[];
}

export default function ScreenshotViewer({ data }: ScreenshotViewerProps) {
  if (data.length === 0) {
    return <div className="empty-state">Нет артефактов</div>;
  }

  return (
    <div className="screenshot-viewer">
      <div className="artifacts-grid">
        {data.map((item) => (
          <div key={item.id} className="artifact-card">
            <div className="artifact-info">
              <div className="artifact-url">{item.url}</div>
              {item.adDomain && (
                <div className="artifact-domain">Домен: {item.adDomain}</div>
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
                  src={`http://localhost:3000/api/artifacts/${item.screenshotPath}`}
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

