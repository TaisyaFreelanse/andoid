import { Device } from '../api/devices';
import './DeviceCard.css';

interface DeviceCardProps {
  device: Device;
  index?: number;
}

export default function DeviceCard({ device, index = 0 }: DeviceCardProps) {
  
  const deviceNumber = String(index + 1).padStart(2, '0');
  
  
  const deviceModel = device.name || device.androidId?.substring(0, 8) || 'SM-G975F';
  
  
  const centerNumber = device.id ? String(parseInt(device.id.slice(-2), 16) % 100).padStart(2, '0') : deviceNumber;

  return (
    <div className="device-phone">
      {}
      <div className="phone-status-bar">
        <div className="status-bar-left">
          <div className="device-otg-badge">OTG</div>
        </div>
        <div className="status-bar-right">
          <div className="status-icon wifi-icon"></div>
          <div className="status-icon battery-icon"></div>
          <div className="status-icon signal-icon"></div>
        </div>
      </div>
      
      
      <div className="phone-top-content">
        <div className="device-number-large">{deviceNumber}</div>
        <div className="device-model-text">{deviceModel}</div>
      </div>
      
      
      <div className="phone-center-number">{centerNumber}</div>
      
      
      <div className="phone-nav-bar">
        <div className="nav-icons">
          <div className="nav-icon phone-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="white">
              <path d="M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"/>
            </svg>
          </div>
          <div className="nav-icon message-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="white">
              <path d="M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z"/>
            </svg>
          </div>
          <div className="nav-icon browser-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="white">
              <path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/>
            </svg>
          </div>
          <div className="nav-icon camera-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="white">
              <path d="M12 12m-3.2 0a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0"/>
              <path d="M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"/>
            </svg>
          </div>
        </div>
        <div className="nav-buttons">
          <div className="nav-button back-btn"></div>
          <div className="nav-button home-btn"></div>
          <div className="nav-button recent-btn"></div>
        </div>
      </div>
    </div>
  );
}

