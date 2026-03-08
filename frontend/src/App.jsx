import { useState, useEffect } from 'react';
import Header from './components/Header';
import LeagueSelector from './components/LeagueSelector';
import StandingsTable from './components/StandingsTable';
import FixtureList from './components/FixtureList';
import LiveMatchTicker from './components/LiveMatchTicker';
import LiveMatchCard from './components/LiveMatchCard';
import FavoriteTeams from './components/FavoriteTeams';
import TeamSearch from './components/TeamSearch';
import NewsTab from './components/NewsTab';
import LeagueInsight from './components/LeagueInsight';
import WorkflowPanel from './components/WorkflowPanel';
import { useWorkflowStream } from './hooks/useWorkflowStream';
import { useLiveScores } from './hooks/useLiveScores';
import { useAuth } from './hooks/useAuth';
import api from './services/api';

function App() {
  const { events, clearEvents, connected: sseConnected } = useWorkflowStream();
  const { matches: liveMatches, connected: wsConnected, subscribe } = useLiveScores();
  const { isAuthenticated } = useAuth();

  const [selectedLeague, setSelectedLeague] = useState('PL');
  const [standings, setStandings] = useState(null);
  const [fixtures, setFixtures] = useState(null);
  const [todaysMatches, setTodaysMatches] = useState([]);
  const [loading, setLoading] = useState(false);
  const [panelOpen, setPanelOpen] = useState(true);
  const [demoMode, setDemoMode] = useState(false);
  const [activeTab, setActiveTab] = useState('standings');
  const [systemStatus, setSystemStatus] = useState(null);

  useEffect(() => {
    if (!selectedLeague) return;
    setLoading(true);
    api.get(`/leagues/${selectedLeague}/standings`)
      .then(res => {
        const data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
        setStandings(data);
      })
      .catch(() => setStandings(null))
      .finally(() => setLoading(false));
  }, [selectedLeague]);

  useEffect(() => {
    if (!selectedLeague) return;
    api.get(`/leagues/${selectedLeague}/fixtures`)
      .then(res => {
        const data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
        setFixtures(data);
      })
      .catch(() => setFixtures(null));
  }, [selectedLeague]);

  useEffect(() => {
    api.get('/live/today')
      .then(res => setTodaysMatches(res.data || []))
      .catch(() => setTodaysMatches([]));
  }, []);

  useEffect(() => {
    if (wsConnected && selectedLeague) {
      subscribe([selectedLeague]);
    }
  }, [wsConnected, selectedLeague, subscribe]);

  useEffect(() => {
    const fetchStatus = () => {
      api.get('/system/status')
        .then(res => setSystemStatus(res.data))
        .catch(() => {});
    };
    fetchStatus();
    const interval = setInterval(fetchStatus, 10000);
    return () => clearInterval(interval);
  }, []);

  const handleReset = () => {
    setSelectedLeague('PL');
    setActiveTab('standings');
    setStandings(null);
    setFixtures(null);
    setTodaysMatches([]);
    clearEvents();
  };

  const toggleDemo = async () => {
    try {
      const res = await api.post('/demo/toggle');
      setDemoMode(res.data.demoMode);
    } catch (e) {
      console.error('Failed to toggle demo mode');
    }
  };

  return (
    <div className="app">
      <Header
        sseConnected={sseConnected}
        wsConnected={wsConnected}
        demoMode={demoMode}
        onToggleDemo={toggleDemo}
        onTogglePanel={() => setPanelOpen(!panelOpen)}
        panelOpen={panelOpen}
        onReset={handleReset}
      />

      <LiveMatchTicker matches={liveMatches.length > 0 ? liveMatches : todaysMatches} />

      <div className="main-layout">
        <div className={`content-area ${panelOpen ? '' : 'full-width'}`}>
          <LeagueSelector selected={selectedLeague} onSelect={(code) => {
            setSelectedLeague(code);
            setActiveTab('standings');
          }} />

          <div className="tab-bar">
            <button className={`tab ${activeTab === 'standings' ? 'active' : ''}`}
                    onClick={() => setActiveTab('standings')}>Standings</button>
            <button className={`tab ${activeTab === 'fixtures' ? 'active' : ''}`}
                    onClick={() => setActiveTab('fixtures')}>Fixtures</button>
            <button className={`tab ${activeTab === 'live' ? 'active' : ''}`}
                    onClick={() => setActiveTab('live')}>Live</button>
            {isAuthenticated && (
              <button className={`tab ${activeTab === 'favorites' ? 'active' : ''}`}
                      onClick={() => setActiveTab('favorites')}>Favorites</button>
            )}
            <button className={`tab ${activeTab === 'news' ? 'active' : ''}`}
                    onClick={() => setActiveTab('news')}>News</button>
            <button className={`tab ${activeTab === 'search' ? 'active' : ''}`}
                    onClick={() => setActiveTab('search')}>Search</button>
          </div>

          <div className="tab-content">
            {loading && <div className="loading-spinner">Loading...</div>}

            {!loading && activeTab === 'standings' && (
              <>
                <LeagueInsight leagueCode={selectedLeague} />
                <StandingsTable standings={standings} leagueCode={selectedLeague} />
              </>
            )}

            {!loading && activeTab === 'fixtures' && (
              <FixtureList fixtures={fixtures} />
            )}

            {activeTab === 'live' && (
              <div className="live-matches-grid">
                {(liveMatches.length > 0 ? liveMatches : todaysMatches)
                  .slice()
                  .sort((a, b) => {
                    const liveStatuses = ['IN_PLAY', 'LIVE', 'PAUSED'];
                    const aLive = liveStatuses.includes(a.status) ? 0 : a.status === 'FINISHED' ? 2 : 1;
                    const bLive = liveStatuses.includes(b.status) ? 0 : b.status === 'FINISHED' ? 2 : 1;
                    return aLive - bLive;
                  })
                  .map(match => (
                  <LiveMatchCard key={match.id} match={match} />
                ))}
                {liveMatches.length === 0 && todaysMatches.length === 0 && (
                  <div className="empty-state">No live matches right now. Try Demo Mode!</div>
                )}
              </div>
            )}

            {activeTab === 'favorites' && isAuthenticated && (
              <FavoriteTeams />
            )}

            {activeTab === 'news' && (
              <NewsTab />
            )}

            {activeTab === 'search' && (
              <TeamSearch onSelectLeague={(code) => {
                setSelectedLeague(code);
                setActiveTab('standings');
              }} />
            )}
          </div>
        </div>

        {panelOpen && (
          <div className="panel-area">
            <WorkflowPanel
              events={events}
              connected={sseConnected}
              systemStatus={systemStatus}
              onClear={clearEvents}
            />
          </div>
        )}
      </div>

      <footer className="app-footer">
        Made with <span className="heart">&#10084;</span> by Sarthak Jain
      </footer>
    </div>
  );
}

export default App;
