import { Link } from 'react-router-dom';
import { Task } from '../api/tasks';
import TaskCard from './TaskCard';
import './TaskList.css';

interface TaskListProps {
  tasks: Task[];
}

export default function TaskList({ tasks }: TaskListProps) {
  if (tasks.length === 0) {
    return <div className="empty-state">Нет задач</div>;
  }

  return (
    <div className="task-list">
      {tasks.map((task) => (
        <Link key={task.id} to={`/tasks/${task.id}`} className="task-link">
          <TaskCard task={task} />
        </Link>
      ))}
    </div>
  );
}

