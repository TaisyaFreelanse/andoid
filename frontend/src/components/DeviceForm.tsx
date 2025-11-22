import { useState } from 'react';
import { devicesApi, CreateDeviceData } from '../api/devices';
import './DeviceForm.css';

interface DeviceFormProps {
  onSuccess: () => void;
  onCancel: () => void;
}

export default function DeviceForm({ onSuccess, onCancel }: DeviceFormProps) {
  const [formData, setFormData] = useState<CreateDeviceData>({
    name: '',
    androidId: '',
    aaid: '',
    browserType: 'chrome',
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      await devicesApi.create(formData);
      onSuccess();
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка создания устройства');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="device-form-overlay">
      <div className="device-form">
        <h3>Создать устройство</h3>
        <form onSubmit={handleSubmit}>
          {error && <div className="error-message">{error}</div>}
          
          <div className="form-group">
            <label htmlFor="name">Название</label>
            <input
              id="name"
              type="text"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              required
              placeholder="Например: Device 1"
            />
          </div>

          <div className="form-group">
            <label htmlFor="androidId">Android ID *</label>
            <input
              id="androidId"
              type="text"
              value={formData.androidId}
              onChange={(e) => setFormData({ ...formData, androidId: e.target.value })}
              required
              placeholder="Уникальный Android ID"
            />
          </div>

          <div className="form-group">
            <label htmlFor="aaid">AAID (опционально)</label>
            <input
              id="aaid"
              type="text"
              value={formData.aaid}
              onChange={(e) => setFormData({ ...formData, aaid: e.target.value })}
              placeholder="Advertising ID"
            />
          </div>

          <div className="form-group">
            <label htmlFor="browserType">Тип браузера</label>
            <select
              id="browserType"
              value={formData.browserType}
              onChange={(e) => setFormData({ ...formData, browserType: e.target.value as 'chrome' | 'webview' })}
              required
            >
              <option value="chrome">Chrome</option>
              <option value="webview">WebView</option>
            </select>
          </div>

          <div className="form-actions">
            <button type="button" onClick={onCancel} className="cancel-btn">
              Отмена
            </button>
            <button type="submit" disabled={loading} className="submit-btn">
              {loading ? 'Создание...' : 'Создать'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

