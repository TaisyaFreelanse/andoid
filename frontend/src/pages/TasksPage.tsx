import { useEffect, useState } from 'react';
import { tasksApi, Task } from '../api/tasks';
import TaskList from '../components/TaskList';
import TaskForm from '../components/TaskForm';
import './TasksPage.css';

export default function TasksPage() {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);

  useEffect(() => {
    loadTasks();
    const interval = setInterval(loadTasks, 3000); 
    return () => clearInterval(interval);
  }, []);

  const loadTasks = async () => {
    try {
      const data = await tasksApi.getAll();
      setTasks(data);
      setError('');
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка загрузки задач');
    } finally {
      setLoading(false);
    }
  };

  const handleTaskCreated = () => {
    setShowForm(false);
    loadTasks();
  };

  if (loading) {
    return <div className="loading">Загрузка задач...</div>;
  }

  return (
    <div className="tasks-page page-shell">
      <section className="page-hero">
        <div className="hero-text">
          <p className="hero-eyebrow">Scenario flow</p>
          <h2>Оркестрация задач</h2>
          <p className="hero-description">
            Планируйте сценарии для phone farm, отслеживайте статусы и моментально реагируйте на
            отклонения. Интерфейс повторяет фирменную эстетику: контрастный фон, монохром и
            нейтральные акценты.
          </p>
        </div>
        <div className="hero-actions">
          <button onClick={() => setShowForm(!showForm)} className="tf-btn primary">
            {showForm ? 'Скрыть форму' : 'Создать задачу'}
          </button>
          <button onClick={loadTasks} className="tf-btn ghost">
            Обновить
          </button>
        </div>
      </section>

      {showForm && (
        <section className="page-section">
          <TaskForm onSuccess={handleTaskCreated} onCancel={() => setShowForm(false)} />
        </section>
      )}

      {error && <div className="error">Ошибка: {error}</div>}

      <section className="page-section">
        <div className="section-heading">
          <h3>Задачи</h3>
          <span className="section-meta">{tasks.length} активных</span>
        </div>
        <TaskList tasks={tasks} onTaskDeleted={loadTasks} />
      </section>
    </div>
  );
}

