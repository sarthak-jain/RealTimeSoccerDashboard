import { useState, useEffect, useRef } from 'react';

function LiveMatchCard({ match }) {
  const [flash, setFlash] = useState(false);
  const prevScore = useRef(null);

  useEffect(() => {
    const scoreKey = `${match.score?.homeGoals}-${match.score?.awayGoals}`;
    if (prevScore.current && prevScore.current !== scoreKey) {
      setFlash(true);
      setTimeout(() => setFlash(false), 2000);
    }
    prevScore.current = scoreKey;
  }, [match.score?.homeGoals, match.score?.awayGoals]);

  const isLive = match.status === 'IN_PLAY' || match.status === 'LIVE' || match.status === 'PAUSED';

  return (
    <div className={`live-match-card ${flash ? 'score-flash' : ''} ${isLive ? 'is-live' : ''}`}>
      {isLive && <div className="live-indicator"><span className="pulse-dot" /> LIVE {match.minute ? `${match.minute}'` : ''}</div>}
      {match.status === 'PAUSED' && <div className="live-indicator ht">HT</div>}
      {match.status === 'FINISHED' && <div className="live-indicator ft">FT</div>}

      <div className="match-league">{match.leagueName || match.leagueCode}</div>

      <div className="match-teams">
        <div className="match-team">
          {match.homeTeam?.crest && <img src={match.homeTeam.crest} alt="" className="team-crest-md" />}
          <span className="team-name">{match.homeTeam?.shortName || match.homeTeam?.name || 'Home'}</span>
        </div>

        <div className="match-score-display">
          <span className="score-number">{match.score?.homeGoals ?? '-'}</span>
          <span className="score-separator">:</span>
          <span className="score-number">{match.score?.awayGoals ?? '-'}</span>
        </div>

        <div className="match-team">
          {match.awayTeam?.crest && <img src={match.awayTeam.crest} alt="" className="team-crest-md" />}
          <span className="team-name">{match.awayTeam?.shortName || match.awayTeam?.name || 'Away'}</span>
        </div>
      </div>

      {match.events && match.events.length > 0 && (
        <div className="match-events">
          {match.events.slice(-3).map((ev, i) => (
            <div key={i} className="match-event">
              <span className="event-minute">{ev.minute}'</span>
              <span className={`event-type ${ev.type?.toLowerCase()}`}>
                {ev.type === 'GOAL' ? '⚽' : ev.type === 'YELLOW_CARD' ? '🟨' : ev.type === 'RED_CARD' ? '🟥' : '🔄'}
              </span>
              <span className="event-player">{ev.player}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default LiveMatchCard;
