function StandingsTable({ standings, leagueCode }) {
  if (!standings) {
    return <div className="empty-state">Select a league to view standings</div>;
  }

  const standingsData = standings?.standings;
  if (!standingsData || !Array.isArray(standingsData) || standingsData.length === 0) {
    return (
      <div className="empty-state">
        <p>No standings data available for this league</p>
      </div>
    );
  }

  const table = standingsData[0]?.table;
  if (!table || !Array.isArray(table)) {
    return <div className="empty-state">No table data available</div>;
  }

  return (
    <div className="standings-container">
      <h2 className="section-title">
        {standings?.competition?.name || leagueCode} — Standings
      </h2>
      <div className="table-wrapper">
        <table className="standings-table">
          <thead>
            <tr>
              <th>#</th>
              <th className="team-col">Team</th>
              <th>P</th>
              <th>W</th>
              <th>D</th>
              <th>L</th>
              <th>GF</th>
              <th>GA</th>
              <th>GD</th>
              <th>Pts</th>
            </tr>
          </thead>
          <tbody>
            {table.map((row) => (
              <tr key={row.team?.id || row.position}>
                <td className="position">{row.position}</td>
                <td className="team-col">
                  <div className="team-cell">
                    {row.team?.crest && (
                      <img src={row.team.crest} alt="" className="team-crest" />
                    )}
                    <span className="team-name">{row.team?.name || 'Unknown'}</span>
                  </div>
                </td>
                <td>{row.playedGames}</td>
                <td>{row.won}</td>
                <td>{row.draw}</td>
                <td>{row.lost}</td>
                <td>{row.goalsFor}</td>
                <td>{row.goalsAgainst}</td>
                <td className={row.goalDifference > 0 ? 'positive' : row.goalDifference < 0 ? 'negative' : ''}>
                  {row.goalDifference > 0 ? '+' : ''}{row.goalDifference}
                </td>
                <td className="points">{row.points}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default StandingsTable;
