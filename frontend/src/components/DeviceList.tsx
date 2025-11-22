import { Device } from '../api/devices';
import DeviceCard from './DeviceCard';
import './DeviceList.css';

interface DeviceListProps {
  devices: Device[];
}

export default function DeviceList({ devices }: DeviceListProps) {
  if (devices.length === 0) {
    return <div className="empty-state">Нет устройств</div>;
  }

  return (
    <div className="device-list">
      {devices.map((device, index) => (
        <DeviceCard key={device.id} device={device} index={index} />
      ))}
    </div>
  );
}

