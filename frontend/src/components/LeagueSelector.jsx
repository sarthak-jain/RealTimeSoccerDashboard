const LEAGUES = [
  { code: 'PL', name: 'Premier League', flag: '\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F' },
  { code: 'PD', name: 'La Liga', flag: '\uD83C\uDDEA\uD83C\uDDF8' },
  { code: 'SA', name: 'Serie A', flag: '\uD83C\uDDEE\uD83C\uDDF9' },
  { code: 'BL1', name: 'Bundesliga', flag: '\uD83C\uDDE9\uD83C\uDDEA' },
  { code: 'FL1', name: 'Ligue 1', flag: '\uD83C\uDDEB\uD83C\uDDF7' },
  { code: 'CL', name: 'Champions League', flag: '\u2B50' },
  { code: 'ELC', name: 'Championship', flag: '\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F' },
  { code: 'DED', name: 'Eredivisie', flag: '\uD83C\uDDF3\uD83C\uDDF1' },
  { code: 'PPL', name: 'Primeira Liga', flag: '\uD83C\uDDF5\uD83C\uDDF9' },
  { code: 'BSA', name: 'Serie A (Brazil)', flag: '\uD83C\uDDE7\uD83C\uDDF7' },
];

function LeagueSelector({ selected, onSelect }) {
  return (
    <div className="league-selector">
      {LEAGUES.map(league => (
        <button
          key={league.code}
          className={`league-btn ${selected === league.code ? 'active' : ''}`}
          onClick={() => onSelect(league.code)}
          title={league.name}
        >
          <span className="league-flag">{league.flag}</span>
          <span className="league-name">{league.name}</span>
        </button>
      ))}
    </div>
  );
}

export default LeagueSelector;
