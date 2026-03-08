function LiveMatchTicker({ matches }) {
  if (!matches || matches.length === 0) return null;

  const liveMatches = matches.filter(m =>
    m.status === 'IN_PLAY' || m.status === 'LIVE' || m.status === 'PAUSED' || m.status === 'FINISHED'
  );

  if (liveMatches.length === 0) return null;

  return (
    <div className="ticker-bar">
      <div className="ticker-label">LIVE</div>
      <div className="ticker-scroll">
        <div className="ticker-content">
          {liveMatches.map(match => (
            <span key={match.id} className="ticker-item">
              <span className="ticker-team">{match.homeTeam?.shortName || match.homeTeam?.name}</span>
              <span className="ticker-score">
                {match.score?.homeGoals ?? '-'} - {match.score?.awayGoals ?? '-'}
              </span>
              <span className="ticker-team">{match.awayTeam?.shortName || match.awayTeam?.name}</span>
              {(match.status === 'IN_PLAY' || match.status === 'LIVE') && match.minute > 0 && (
                <span className="ticker-minute">{match.minute}'</span>
              )}
              {match.status === 'FINISHED' && <span className="ticker-ft">FT</span>}
              {match.status === 'PAUSED' && <span className="ticker-ht">HT</span>}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

export default LiveMatchTicker;
