import { Task } from '../api/tasks';
import './TaskCard.css';

interface TaskCardProps {
  task: Task;
}

export default function TaskCard({ task }: TaskCardProps) {
  const getStatusColor = (status: Task['status']) => {
    switch (status) {
      case 'pending':
        return '#95a5a6';
      case 'assigned':
        return '#3498db';
      case 'running':
        return '#f39c12';
      case 'completed':
        return '#27ae60';
      case 'failed':
        return '#e74c3c';
      case 'cancelled':
        return '#7f8c8d';
      default:
        return '#95a5a6';
    }
  };

  return (
    <div className="task-card">
      <div className="task-header">
        <h3>{task.name}</h3>
        <span
          className="status-badge"
          style={{ backgroundColor: getStatusColor(task.status) }}
        >
          {task.status}
        </span>
      </div>
      <div className="task-info">
        <div className="info-row">
          <span className="label">Тип:</span>
          <span className="value">{task.type}</span>
        </div>
        {task.device && (
          <div className="info-row">
            <span className="label">Устройство:</span>
            <span className="value">{task.device.name || task.device.androidId}</span>
          </div>
        )}
        <div className="info-row">
          <span className="label">Создана:</span>
          <span className="value">{new Date(task.createdAt).toLocaleString('ru-RU')}</span>
        </div>
      </div>
    </div>
  );
}

