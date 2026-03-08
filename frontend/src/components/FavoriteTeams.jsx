import { useState, useEffect } from 'react';
import { useAuth } from '../hooks/useAuth';
import api from '../services/api';

function FavoriteTeams() {
  const { isAuthenticated } = useAuth();
  const [favorites, setFavorites] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!isAuthenticated) return;
    setLoading(true);
    api.get('/favorites')
      .then(res => setFavorites(res.data || []))
      .catch(() => setFavorites([]))
      .finally(() => setLoading(false));
  }, [isAuthenticated]);

  const removeFavorite = async (teamId) => {
    try {
      await api.delete(`/favorites/${teamId}`);
      setFavorites(prev => prev.filter(f => f.teamId !== teamId));
    } catch (e) {
      console.error('Failed to remove favorite');
    }
  };

  if (!isAuthenticated) {
    return <div className="empty-state">Log in to manage your favorite teams</div>;
  }

  if (loading) {
    return <div className="loading-spinner">Loading favorites...</div>;
  }

  return (
    <div className="favorites-container">
      <h2 className="section-title">Favorite Teams</h2>
      {favorites.length === 0 ? (
        <div className="empty-state">
          No favorite teams yet. Search for a team to add it to your favorites.
        </div>
      ) : (
        <div className="favorites-grid">
          {favorites.map(fav => (
            <div key={fav.teamId} className="favorite-card">
              {fav.teamLogoUrl && <img src={fav.teamLogoUrl} alt="" className="team-crest-lg" />}
              <div className="favorite-info">
                <span className="favorite-name">{fav.teamName}</span>
                <span className="favorite-league">{fav.leagueName}</span>
              </div>
              <button className="btn-remove" onClick={() => removeFavorite(fav.teamId)}>Remove</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default FavoriteTeams;
