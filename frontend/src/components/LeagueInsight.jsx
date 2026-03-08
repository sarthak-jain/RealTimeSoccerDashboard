import { useState, useEffect } from 'react';
import api from '../services/api';

function LeagueInsight({ leagueCode }) {
  const [insight, setInsight] = useState(null);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!leagueCode) return;
    setInsight(null);
    setError(null);
    setLoading(true);

    api.get(`/insights/${leagueCode}`)
      .then(res => {
        if (res.data?.insight) {
          setInsight(res.data);
        } else if (res.data?.error) {
          setError(res.data.error);
        }
      })
      .catch(() => setError('Failed to generate insight'))
      .finally(() => setLoading(false));
  }, [leagueCode]);

  if (loading) {
    return (
      <div className="insight-card loading">
        <div className="insight-header">
          <span className="insight-icon">🤖</span>
          <span className="insight-title">AI Analysis</span>
          <span className="insight-loading-dots">Analyzing standings...</span>
        </div>
        <div className="insight-shimmer" />
      </div>
    );
  }

  if (error || !insight?.insight) {
    return null;
  }

  return (
    <div className="insight-card">
      <div className="insight-header">
        <span className="insight-icon">&#129302;</span>
        <span className="insight-title">AI Analysis — {insight.leagueName}</span>
        <span className="insight-meta">
          <span className="insight-model">{insight.model}</span>
        </span>
      </div>
      {!expanded ? (
        <div className="insight-body collapsed">
          <p className="insight-paragraph insight-preview">
            {insight.insight}
          </p>
          <button className="insight-readmore" onClick={() => setExpanded(true)}>
            Read more
          </button>
        </div>
      ) : (
        <div className="insight-body">
          {insight.insight.split('\n\n').map((para, i) => (
            <p key={i} className="insight-paragraph">{para}</p>
          ))}
          <button className="insight-readmore" onClick={() => setExpanded(false)}>
            Show less
          </button>
        </div>
      )}
    </div>
  );
}

export default LeagueInsight;
