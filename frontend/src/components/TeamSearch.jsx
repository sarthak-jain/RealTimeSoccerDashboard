import { useState, useRef, useCallback } from 'react';
import api from '../services/api';
import { useAuth } from '../hooks/useAuth';

function TeamSearch({ onSelectLeague }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [loading, setLoading] = useState(false);
  const [selectedTeam, setSelectedTeam] = useState(null);
  const { isAuthenticated, token } = useAuth();
  const debounceRef = useRef(null);

  const handleSearch = useCallback((q) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!q || q.length < 2) {
      setResults(null);
      return;
    }
    debounceRef.current = setTimeout(async () => {
      setLoading(true);
      try {
        const res = await api.get('/search', { params: { q } });
        setResults(res.data);
      } catch (e) {
        setResults(null);
      } finally {
        setLoading(false);
      }
    }, 300);
  }, []);

  const handleLeagueClick = (league) => {
    if (onSelectLeague) {
      onSelectLeague(league.code);
    }
  };

  const handleTeamClick = (team) => {
    setSelectedTeam(selectedTeam?.id === team.id ? null : team);
  };

  const handleAddFavorite = async (team) => {
    try {
      await api.post('/favorites', {
        teamId: team.id,
        teamName: team.name,
        teamLogoUrl: team.crest || '',
      });
      alert('Added ' + team.name + ' to favorites!');
    } catch (e) {
      if (e.response?.status === 409) {
        alert(team.name + ' is already in your favorites');
      } else {
        alert('Failed to add favorite. Make sure you are logged in.');
      }
    }
  };

  return (
    <div className="search-container">
      <h2 className="section-title">Search</h2>
      <div className="search-input-wrapper">
        <input
          type="text"
          className="search-input"
          placeholder="Search leagues or teams..."
          value={query}
          onChange={e => {
            setQuery(e.target.value);
            handleSearch(e.target.value);
          }}
        />
        {loading && <span className="search-spinner">...</span>}
      </div>

      {results && (
        <div className="search-results">
          {results.leagues && results.leagues.length > 0 && (
            <div className="search-section">
              <h3>Leagues</h3>
              {results.leagues.map(league => (
                <div key={league.code} className="search-result-item clickable"
                     onClick={() => handleLeagueClick(league)}>
                  <span className="result-name">{league.name}</span>
                  <span className="result-code">{league.code}</span>
                  <span className="result-action-hint">View standings</span>
                </div>
              ))}
            </div>
          )}

          {results.teams && results.teams.length > 0 && (
            <div className="search-section">
              <h3>Teams</h3>
              {results.teams.map(team => (
                <div key={team.id}>
                  <div className="search-result-item clickable"
                       onClick={() => handleTeamClick(team)}>
                    {team.crest && <img src={team.crest} alt="" className="team-crest-sm" />}
                    <span className="result-name">{team.name}</span>
                    {team.area && <span className="result-area">{team.area}</span>}
                  </div>
                  {selectedTeam?.id === team.id && (
                    <div className="team-detail-card">
                      <div className="team-detail-header">
                        {team.crest && <img src={team.crest} alt="" className="team-detail-crest" />}
                        <div>
                          <div className="team-detail-name">{team.name}</div>
                          {team.shortName && <div className="team-detail-short">{team.shortName} ({team.tla})</div>}
                          {team.area && <div className="team-detail-area">{team.area}</div>}
                        </div>
                      </div>
                      {isAuthenticated && (
                        <button className="btn-favorite" onClick={() => handleAddFavorite(team)}>
                          + Add to Favorites
                        </button>
                      )}
                      {!isAuthenticated && (
                        <p className="team-detail-hint">Log in to add to favorites</p>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {(!results.leagues || results.leagues.length === 0) &&
           (!results.teams || results.teams.length === 0) && (
            <div className="empty-state">No results found for "{query}"</div>
          )}
        </div>
      )}
    </div>
  );
}

export default TeamSearch;
