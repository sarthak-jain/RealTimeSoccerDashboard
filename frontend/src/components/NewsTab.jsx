import { useState, useEffect } from 'react';
import api from '../services/api';

function NewsTab() {
  const [articles, setArticles] = useState([]);
  const [brief, setBrief] = useState(null);
  const [briefLoading, setBriefLoading] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    setLoading(true);
    api.get('/news')
      .then(res => {
        setArticles(res.data?.articles || []);
        setError(null);
      })
      .catch(err => {
        setError('Failed to load news');
        setArticles([]);
      })
      .finally(() => setLoading(false));

    setBriefLoading(true);
    api.get('/news/brief')
      .then(res => {
        if (res.data?.brief) {
          setBrief(res.data);
        }
      })
      .catch(() => {})
      .finally(() => setBriefLoading(false));
  }, []);

  if (loading) {
    return <div className="loading-spinner">Loading news...</div>;
  }

  if (error) {
    return (
      <div className="empty-state">
        <p>{error}</p>
        <p style={{ fontSize: '0.8rem', marginTop: '8px', color: '#8892b0' }}>
          Check that the <code>GNEWS_API_KEY</code> environment variable is set.
        </p>
      </div>
    );
  }

  if (articles.length === 0) {
    return <div className="empty-state">No soccer news available right now</div>;
  }

  return (
    <div className="news-container">
      {/* Today's Soccer Brief */}
      {briefLoading && (
        <div className="news-brief-card loading">
          <div className="brief-header">
            <span className="brief-icon">&#9889;</span>
            <span className="brief-title">Today's Soccer Brief</span>
          </div>
          <div className="brief-shimmer">
            <div className="shimmer-line" style={{ width: '100%' }} />
            <div className="shimmer-line" style={{ width: '90%' }} />
            <div className="shimmer-line" style={{ width: '75%' }} />
          </div>
        </div>
      )}
      {!briefLoading && brief?.brief && (
        <div className="news-brief-card">
          <div className="brief-header">
            <span className="brief-icon">&#9889;</span>
            <span className="brief-title">Today's Soccer Brief</span>
            <span className="brief-badge">AI Generated</span>
          </div>
          <p className="brief-text">{brief.brief}</p>
          <div className="brief-meta">
            <span>Summarized from {brief.articleCount} articles</span>
            <span className="brief-model">{brief.model}</span>
          </div>
        </div>
      )}

      <h2 className="section-title">Latest Articles</h2>
      <div className="news-grid">
        {articles.map((article, i) => (
          <a
            key={i}
            href={article.url}
            target="_blank"
            rel="noopener noreferrer"
            className="news-card"
          >
            {article.image && (
              <div className="news-image-wrapper">
                <img
                  src={article.image}
                  alt=""
                  className="news-image"
                  onError={e => { e.target.style.display = 'none'; }}
                />
              </div>
            )}
            <div className="news-content">
              <h3 className="news-title">{article.title}</h3>
              <p className="news-description">{article.description}</p>
              <div className="news-meta">
                <span className="news-source">{article.source?.name}</span>
                <span className="news-date">
                  {article.publishedAt ? new Date(article.publishedAt).toLocaleDateString('en-US', {
                    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
                  }) : ''}
                </span>
              </div>
            </div>
          </a>
        ))}
      </div>
    </div>
  );
}

export default NewsTab;
