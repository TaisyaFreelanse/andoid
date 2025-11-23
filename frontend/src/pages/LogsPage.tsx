import { useEffect, useRef, useState } from 'react';
import './LogsPage.css';

export default function LogsPage() {
  const [logs, setLogs] = useState<string[]>([]);
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const logsEndRef = useRef<HTMLDivElement>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const pingIntervalRef = useRef<number | null>(null);
  const shouldReconnectRef = useRef(true);

  const connectWebSocket = () => {
    
    const authStorage = localStorage.getItem('auth-storage');
    let token: string | null = null;
    try {
      if (authStorage) {
        const authData = JSON.parse(authStorage);
        token = authData?.state?.token || null;
      }
    } catch (e) {
      console.error('Error parsing auth storage:', e);
    }


    // Get API URL from environment or use backend URL for production
    const apiBaseUrl = import.meta.env.VITE_API_URL || 
      (typeof window !== 'undefined' && window.location.hostname === 'localhost'
        ? 'http://localhost:3000'
        : 'https://android-automation-backend.onrender.com');
    
    // Extract host from API URL for WebSocket
    const apiUrl = new URL(apiBaseUrl);
    const wsProtocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsHost = apiUrl.host;
    const wsUrl = `${wsProtocol}//${wsHost}/api/logs/stream${token ? `?token=${token}` : ''}`;

    // Убираем лишние логи для более чистого консоли
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      setConnected(true);
      
      // Отправляем начальный ping
      try {
        ws.send(JSON.stringify({ type: 'ping' }));
      } catch (error) {
        console.error('Error sending ping:', error);
      }
      
      // Устанавливаем периодический ping каждые 2 секунды, чтобы Render не закрывал idle соединения
      pingIntervalRef.current = window.setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          try {
            ws.send(JSON.stringify({ type: 'ping' }));
          } catch (error) {
            console.error('Error sending periodic ping:', error);
          }
        }
      }, 2000);
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        
        if (data.type === 'connected') {
          setConnected(true);
          setLogs((prev) => [...prev.slice(-999), `[${new Date().toLocaleTimeString()}] ${data.message}`]);
        } else if (data.type === 'log') {
          const logMessage = data.message || JSON.stringify(data.data || {});
          const timestamp = data.timestamp ? new Date(data.timestamp).toLocaleTimeString() : '';
          setLogs((prev) => [...prev.slice(-999), `[${timestamp}] ${logMessage}`]);
        } else if (data.type === 'heartbeat') {

        } else if (data.type === 'pong') {
          // Pong получен - соединение активно, не логируем
        } else {
          
          setLogs((prev) => [...prev.slice(-999), `[${new Date().toLocaleTimeString()}] ${JSON.stringify(data)}`]);
        }
      } catch (error) {
        
        setLogs((prev) => [...prev.slice(-999), `[${new Date().toLocaleTimeString()}] ${event.data}`]);
      }
    };

    ws.onerror = (error) => {
      console.error('WebSocket error:', error);
      setConnected(false);
    };

    ws.onclose = (event) => {
      // Коды 1005, 1006 - Render закрывает idle соединения (нормальное поведение)
      // Не логируем эти коды, так как это ожидаемое поведение
      if (event.code !== 1005 && event.code !== 1006) {
        console.log(`WebSocket closed: ${event.code} ${event.reason || ''}`);
      }
      setConnected(false);
      
      // Очищаем ping interval при закрытии соединения
      if (pingIntervalRef.current) {
        clearInterval(pingIntervalRef.current);
        pingIntervalRef.current = null;
      }
      
      // Автоматическое переподключение для кодов 1005, 1006 (Render закрывает idle соединения)
      // Коды 1000, 1001 - нормальное закрытие, не переподключаемся
      if (shouldReconnectRef.current && event.code !== 1000 && event.code !== 1001) {
        // Тихая переподключение без лишних логов
        reconnectTimeoutRef.current = setTimeout(() => {
          if (shouldReconnectRef.current) {
            connectWebSocket();
          }
        }, 2000); // Уменьшаем задержку до 2 секунд для более быстрого переподключения
      }
    };

    return () => {
      shouldReconnectRef.current = false;
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
      if (pingIntervalRef.current) {
        clearInterval(pingIntervalRef.current);
        pingIntervalRef.current = null;
      }
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  };

  useEffect(() => {
    shouldReconnectRef.current = true;
    connectWebSocket();

    return () => {
      shouldReconnectRef.current = false;
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
      if (pingIntervalRef.current) {
        clearInterval(pingIntervalRef.current);
        pingIntervalRef.current = null;
      }
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  return (
    <div className="logs-page page-shell">
      <section className="page-hero">
        <div className="hero-text">
          <p className="hero-eyebrow">Realtime</p>
          <h2>Телеметрия инфраструктуры</h2>
          <p className="hero-description">
            Прямое подключение к Fastify WebSocket. Мы фиксируем только состояние канала — реальные
            Android-агенты появятся на следующем этапе, но визуал отражает эстетику phone farm.
          </p>
        </div>
        <div className="hero-actions">
          <span className={`status-pill ${connected ? 'success' : 'idle'}`}>
            {connected ? 'Online' : 'Offline'}
          </span>
          <p className="hero-description" style={{ textAlign: 'right' }}>
            Endpoint: {(() => {
              const apiBaseUrl = import.meta.env.VITE_API_URL || 
                (typeof window !== 'undefined' && window.location.hostname === 'localhost'
                  ? 'http://localhost:3000'
                  : 'https://android-automation-backend.onrender.com');
              const apiUrl = new URL(apiBaseUrl);
              const wsProtocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
              return `${wsProtocol}//${apiUrl.host}/api/logs/stream`;
            })()}
          </p>
        </div>
      </section>

      <section className="page-section logs-shell">
        <div className="logs-container">
          {logs.length === 0 ? (
            <div className="empty-logs">
              {connected ? (
                <div>
                  <div style={{ fontSize: '1.2rem', marginBottom: '1rem', color: '#27ae60' }}>
                    ✓ WebSocket подключен
                  </div>
                  <div style={{ color: 'rgba(255, 255, 255, 0.7)' }}>
                    Логи будут отображаться здесь после подключения Android Agent (Этап 3).
                    <br />
                    На Этапе 2 это нормальное поведение - инфраструктура готова.
                  </div>
                </div>
              ) : (
                <div>
                  <div style={{ fontSize: '1.2rem', marginBottom: '1rem', color: 'rgba(255, 255, 255, 0.5)' }}>
                    ⚠ WebSocket не подключен
                  </div>
                  <div style={{ color: 'rgba(255, 255, 255, 0.6)' }}>
                    Попытка подключения к серверу...
                    <br />
                    <small style={{ fontSize: '0.85rem', marginTop: '0.5rem', display: 'block' }}>
                      На Этапе 2 это ожидаемо - реальные логи появятся на Этапе 3 с Android Agent
                    </small>
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="logs-content">
              {logs.map((log, index) => (
                <div key={index} className="log-line">
                  {log}
                </div>
              ))}
              <div ref={logsEndRef} />
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

