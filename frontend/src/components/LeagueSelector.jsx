const LEAGUES = [
  { code: 'PL', name: 'Premier League', logo: 'https://crests.football-data.org/PL.png' },
  { code: 'PD', name: 'La Liga', logo: 'https://crests.football-data.org/PD.png' },
  { code: 'SA', name: 'Serie A', logo: 'https://crests.football-data.org/SA.png' },
  { code: 'BL1', name: 'Bundesliga', logo: 'https://crests.football-data.org/BL1.png' },
  { code: 'FL1', name: 'Ligue 1', logo: 'https://crests.football-data.org/FL1.png' },
  { code: 'CL', name: 'Champions League', logo: 'https://crests.football-data.org/CL.png' },
  { code: 'ELC', name: 'Championship', logo: 'https://crests.football-data.org/ELC.png' },
  { code: 'DED', name: 'Eredivisie', logo: 'https://crests.football-data.org/DED.png' },
  { code: 'PPL', name: 'Primeira Liga', logo: 'https://crests.football-data.org/PPL.png' },
  { code: 'BSA', name: 'Serie A (Brazil)', logo: 'https://crests.football-data.org/BSA.png' },
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
          <img src={league.logo} alt="" className="league-logo" onError={(e) => { e.target.style.display = 'none'; }} />
          <span className="league-name">{league.name}</span>
        </button>
      ))}
    </div>
  );
}

export default LeagueSelector;
