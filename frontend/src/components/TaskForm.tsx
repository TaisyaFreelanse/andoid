import { useState, useEffect } from 'react';
import { tasksApi, TaskType } from '../api/tasks';
import { devicesApi, Device } from '../api/devices';
import './TaskForm.css';

interface TaskFormProps {
  onSuccess: () => void;
  onCancel: () => void;
}

export default function TaskForm({ onSuccess, onCancel }: TaskFormProps) {
  const [name, setName] = useState('');
  const [type, setType] = useState<TaskType>('parsing');
  const [configJson, setConfigJson] = useState('{}');
  const [deviceId, setDeviceId] = useState('');
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    loadDevices();
  }, []);

  const loadDevices = async () => {
    try {
      const data = await devicesApi.getAll();
      setDevices(data);
      // Автоматически выбрать первое онлайн устройство
      const onlineDevice = data.find(d => d.status === 'online');
      if (onlineDevice) {
        setDeviceId(onlineDevice.id);
      } else if (data.length > 0) {
        setDeviceId(data[0].id);
      }
    } catch (err) {
      console.error('Ошибка загрузки устройств:', err);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    let parsedConfig;
    try {
      parsedConfig = JSON.parse(configJson);
    } catch (err) {
      setError('Неверный JSON в конфигурации');
      return;
    }

    setLoading(true);
    try {
      await tasksApi.create({
        name,
        type,
        configJson: parsedConfig,
        deviceId: deviceId || undefined,
      });
      onSuccess();
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка создания задачи');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="task-form-container">
      <form onSubmit={handleSubmit} className="task-form">
        <h3>Создать задачу</h3>
        {error && <div className="error-message">{error}</div>}
        <div className="form-group">
          <label htmlFor="name">Название</label>
          <input
            id="name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        <div className="form-group">
          <label htmlFor="type">Тип</label>
          <select
            id="type"
            value={type}
            onChange={(e) => setType(e.target.value as TaskType)}
            required
          >
            <option value="surfing">Серфинг</option>
            <option value="parsing">Парсинг</option>
            <option value="uniqueness">Уникализация</option>
            <option value="screenshot">Скриншот</option>
          </select>
        </div>
        <div className="form-group">
          <label htmlFor="device">Устройство</label>
          <select
            id="device"
            value={deviceId}
            onChange={(e) => setDeviceId(e.target.value)}
            required
          >
            <option value="">-- Выберите устройство --</option>
            {devices.map((device, index) => {
              const deviceNumber = String(index + 1).padStart(2, '0');
              return (
                <option key={device.id} value={device.id}>
                  {deviceNumber} - {device.name || device.androidId?.substring(0, 8) || 'Device'} ({device.status}) {device.status === 'online' ? '🟢' : '⚪'}
                </option>
              );
            })}
          </select>
        </div>
        <div className="form-group">
          <label htmlFor="config">Конфигурация (JSON)</label>
          <textarea
            id="config"
            value={configJson}
            onChange={(e) => setConfigJson(e.target.value)}
            rows={10}
            required
          />
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
  );
}

