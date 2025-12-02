import { useEffect, useState } from 'react';
import { devicesApi, Device } from '../api/devices';
import DeviceList from '../components/DeviceList';
import DeviceForm from '../components/DeviceForm';
import './DevicesPage.css';

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);

  useEffect(() => {
    loadDevices();
    const interval = setInterval(loadDevices, 5000); 
    return () => clearInterval(interval);
  }, []);

  const loadDevices = async () => {
    try {
      const data = await devicesApi.getAll();
      setDevices(data);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка загрузки устройств');
    } finally {
      setLoading(false);
    }
  };

  const handleDeviceCreated = () => {
    setShowForm(false);
    loadDevices();
  };

  const handleDeleteDevice = async (id: string) => {
    try {
      await devicesApi.delete(id);
      loadDevices();
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка удаления устройства');
    }
  };

  if (loading) {
    return <div className="loading">Загрузка устройств...</div>;
  }

  return (
    <div className="devices-page page-shell">
      <section className="page-hero">
        <div className="hero-text">
          <p className="hero-eyebrow">Phone farm</p>
          <h2>Цифровой парк устройств</h2>
          <p className="hero-description">
            Контролируйте подключённые устройства, их статус и готовность к выполнению сценариев.
          </p>
        </div>
        <div className="hero-actions">
          <button onClick={() => setShowForm(true)} className="tf-btn primary">
            Создать устройство
          </button>
          <button onClick={loadDevices} className="tf-btn ghost">
            Обновить список
          </button>
        </div>
      </section>

      {error && <div className="error">Ошибка: {error}</div>}

      {showForm && (
        <section className="page-section">
          <DeviceForm onSuccess={handleDeviceCreated} onCancel={() => setShowForm(false)} />
        </section>
      )}

      <section className="page-section">
        <div className="section-heading">
          <h3>Устройства</h3>
          <span className="section-meta">{devices.length} активных</span>
        </div>
        <DeviceList devices={devices} onDelete={handleDeleteDevice} />
      </section>
    </div>
  );
}

