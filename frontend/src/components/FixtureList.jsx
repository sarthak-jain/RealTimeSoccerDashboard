function FixtureList({ fixtures }) {
  if (!fixtures) {
    return <div className="empty-state">No fixtures available</div>;
  }

  const matches = fixtures?.matches;
  if (!matches || !Array.isArray(matches) || matches.length === 0) {
    return (
      <div className="empty-state">
        <p>No upcoming fixtures</p>
      </div>
    );
  }

  // Group by date
  const grouped = {};
  for (const match of matches) {
    const date = match.utcDate ? new Date(match.utcDate).toLocaleDateString('en-US', {
      weekday: 'short', month: 'short', day: 'numeric'
    }) : 'Unknown';
    if (!grouped[date]) grouped[date] = [];
    grouped[date].push(match);
  }

  const statusBadge = (status) => {
    if (status === 'IN_PLAY' || status === 'LIVE') return <span className="status-badge live">LIVE</span>;
    if (status === 'PAUSED') return <span className="status-badge paused">HT</span>;
    if (status === 'FINISHED') return <span className="status-badge finished">FT</span>;
    return null;
  };

  return (
    <div className="fixtures-container">
      <h2 className="section-title">Upcoming Fixtures</h2>
      {Object.entries(grouped).map(([date, dateMatches]) => (
        <div key={date} className="fixture-group">
          <div className="fixture-date">{date}</div>
          {dateMatches.map(match => (
            <div key={match.id} className="fixture-row">
              <div className="fixture-team home">
                {match.homeTeam?.crest && <img src={match.homeTeam.crest} alt="" className="team-crest-sm" />}
                <span>{match.homeTeam?.shortName || match.homeTeam?.name || 'TBD'}</span>
              </div>
              <div className="fixture-score">
                {match.status === 'SCHEDULED' || match.status === 'TIMED' ? (
                  <span className="fixture-time">
                    {match.utcDate ? new Date(match.utcDate).toLocaleTimeString('en-US', {
                      hour: '2-digit', minute: '2-digit'
                    }) : '-'}
                  </span>
                ) : (
                  <span className="fixture-result">
                    {match.score?.fullTime?.home ?? '-'} - {match.score?.fullTime?.away ?? '-'}
                  </span>
                )}
                {statusBadge(match.status)}
              </div>
              <div className="fixture-team away">
                <span>{match.awayTeam?.shortName || match.awayTeam?.name || 'TBD'}</span>
                {match.awayTeam?.crest && <img src={match.awayTeam.crest} alt="" className="team-crest-sm" />}
              </div>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

export default FixtureList;
