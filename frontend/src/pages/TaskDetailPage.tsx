import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { tasksApi, Task } from '../api/tasks';
import './TaskDetailPage.css';

export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [task, setTask] = useState<Task | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (id) {
      loadTask();
      const interval = setInterval(loadTask, 2000); 
      return () => clearInterval(interval);
    }
  }, [id]);

  const loadTask = async () => {
    if (!id) return;
    try {
      const data = await tasksApi.getById(id);
      setTask(data);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка загрузки задачи');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="loading">Загрузка задачи...</div>;
  }

  if (error || !task) {
    return (
      <div className="error">
        {error || 'Задача не найдена'}
        <Link to="/tasks" className="back-link">
          Вернуться к списку
        </Link>
      </div>
    );
  }

  return (
    <div className="task-detail">
      <div className="page-header">
        <h2>{task.name}</h2>
        <Link to="/tasks" className="back-link">
          ← Назад к списку
        </Link>
      </div>

      <div className="task-info">
        <div className="info-section">
          <h3>Основная информация</h3>
          <div className="info-grid">
            <div className="info-item">
              <span className="label">Статус:</span>
              <span className={`status status-${task.status}`}>{task.status}</span>
            </div>
            <div className="info-item">
              <span className="label">Тип:</span>
              <span className="value">{task.type}</span>
            </div>
            <div className="info-item">
              <span className="label">Создана:</span>
              <span className="value">{new Date(task.createdAt).toLocaleString('ru-RU')}</span>
            </div>
            {task.startedAt && (
              <div className="info-item">
                <span className="label">Начата:</span>
                <span className="value">{new Date(task.startedAt).toLocaleString('ru-RU')}</span>
              </div>
            )}
            {task.completedAt && (
              <div className="info-item">
                <span className="label">Завершена:</span>
                <span className="value">{new Date(task.completedAt).toLocaleString('ru-RU')}</span>
              </div>
            )}
          </div>
        </div>

        {task.device && (
          <div className="info-section">
            <h3>Устройство</h3>
            <div className="info-grid">
              <div className="info-item">
                <span className="label">Имя:</span>
                <span className="value">{task.device.name || task.device.androidId}</span>
              </div>
              <div className="info-item">
                <span className="label">Android ID:</span>
                <span className="value">{task.device.androidId}</span>
              </div>
            </div>
          </div>
        )}

        {task.configJson && (
          <div className="info-section">
            <h3>Конфигурация</h3>
            <pre className="config-json">{JSON.stringify(task.configJson, null, 2)}</pre>
          </div>
        )}

        {task.resultJson && (
          <div className="info-section">
            <h3>Результат</h3>
            <pre className="result-json">{JSON.stringify(task.resultJson, null, 2)}</pre>
          </div>
        )}

        {task.parsedData && task.parsedData.length > 0 && (
          <div className="info-section">
            <h3>Спарсенные данные ({task.parsedData.length})</h3>
            <div className="parsed-data-list">
              {task.parsedData.map((item: any) => (
                <div key={item.id} className="parsed-data-item">
                  <div className="parsed-url">{item.url}</div>
                  {item.adDomain && (
                    <div className="parsed-domain">Домен: {item.adDomain}</div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

