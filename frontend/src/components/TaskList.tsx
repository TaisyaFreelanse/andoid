import { Link } from 'react-router-dom';
import { Task, tasksApi } from '../api/tasks';
import TaskCard from './TaskCard';
import './TaskList.css';

interface TaskListProps {
  tasks: Task[];
  onTaskDeleted?: () => void;
}

export default function TaskList({ tasks, onTaskDeleted }: TaskListProps) {
  const handleDelete = async (e: React.MouseEvent, taskId: string) => {
    e.preventDefault();
    e.stopPropagation();
    
    if (!confirm('Удалить задачу?')) return;
    
    try {
      await tasksApi.delete(taskId);
      onTaskDeleted?.();
    } catch (err) {
      alert('Ошибка удаления задачи');
    }
  };

  if (tasks.length === 0) {
    return <div className="empty-state">Нет задач</div>;
  }

  return (
    <div className="task-list">
      {tasks.map((task) => (
        <div key={task.id} className="task-item-wrapper">
          <Link to={`/tasks/${task.id}`} className="task-link">
            <TaskCard task={task} />
          </Link>
          <button 
            className="delete-btn task-delete-btn" 
            onClick={(e) => handleDelete(e, task.id)}
            title="Удалить задачу"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}

