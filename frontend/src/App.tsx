import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './stores/authStore';
import LoginPage from './pages/LoginPage';
import DevicesPage from './pages/DevicesPage';
import TasksPage from './pages/TasksPage';
import TaskDetailPage from './pages/TaskDetailPage';
import LogsPage from './pages/LogsPage';
import ArtifactsPage from './pages/ArtifactsPage';
import Layout from './components/Layout';
import AuthGuard from './components/AuthGuard';

function App() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  return (
    <Routes>
      <Route path="/login" element={isAuthenticated ? <Navigate to="/devices" /> : <LoginPage />} />
      <Route
        path="/"
        element={
          <AuthGuard>
            <Layout />
          </AuthGuard>
        }
      >
        <Route index element={<Navigate to="/devices" />} />
        <Route path="devices" element={<DevicesPage />} />
        <Route path="tasks" element={<TasksPage />} />
        <Route path="tasks/:id" element={<TaskDetailPage />} />
        <Route path="logs" element={<LogsPage />} />
        <Route path="artifacts" element={<ArtifactsPage />} />
      </Route>
    </Routes>
  );
}

export default App;

