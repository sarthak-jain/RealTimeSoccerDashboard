import { useState, useEffect, useRef } from 'react';

function SystemArchDiagram({ events, systemStatus }) {
  const [activeArrows, setActiveArrows] = useState({});
  const timeoutsRef = useRef({});

  // Watch events and light up arrows
  useEffect(() => {
    if (events.length === 0) return;
    const lastEvent = events[events.length - 1];
    if (lastEvent.eventType !== 'WORKFLOW_STEP') return;

    const type = lastEvent.type;
    let arrow = null;

    if (type === 'EXTERNAL_API' || type === 'API_SOURCE_SELECT') arrow = 'api-to-backend';
    else if (type === 'CACHE_CHECK' || type === 'CACHE_WRITE') arrow = 'backend-to-redis';
    else if (type === 'DB_READ' || type === 'DB_WRITE') arrow = 'backend-to-db';
    else if (type === 'WEBSOCKET_FANOUT') arrow = 'backend-to-clients';
    else if (type === 'RESPONSE' || type === 'API_GATEWAY') arrow = 'backend-to-clients';
    else if (type === 'AUTH_CHECK') arrow = 'backend-to-db';

    if (arrow) {
      setActiveArrows(prev => ({ ...prev, [arrow]: true }));
      if (timeoutsRef.current[arrow]) clearTimeout(timeoutsRef.current[arrow]);
      timeoutsRef.current[arrow] = setTimeout(() => {
        setActiveArrows(prev => ({ ...prev, [arrow]: false }));
      }, 800);
    }
  }, [events]);

  const boxStyle = (active) => ({
    fill: active ? '#2a2a5a' : '#1a1a3a',
    stroke: active ? '#5c6bc0' : '#333366',
    strokeWidth: 2,
    rx: 8,
  });

  const arrowColor = (key) => activeArrows[key] ? '#5c6bc0' : '#333366';
  const arrowWidth = (key) => activeArrows[key] ? 3 : 1.5;

  return (
    <div className="arch-diagram">
      <svg viewBox="0 0 500 120" className="arch-svg">
        {/* APIs box */}
        <rect x="5" y="35" width="80" height="50" {...boxStyle(activeArrows['api-to-backend'])} />
        <text x="45" y="58" textAnchor="middle" className="arch-label">APIs</text>
        <text x="45" y="72" textAnchor="middle" className="arch-sublabel">Football-Data</text>

        {/* Arrow: APIs -> Backend */}
        <line x1="85" y1="60" x2="145" y2="60"
              stroke={arrowColor('api-to-backend')}
              strokeWidth={arrowWidth('api-to-backend')}
              className={activeArrows['api-to-backend'] ? 'arrow-pulse' : ''} />
        <polygon points="140,55 150,60 140,65"
                 fill={arrowColor('api-to-backend')} />

        {/* Backend box */}
        <rect x="150" y="25" width="100" height="70" {...boxStyle(true)} />
        <text x="200" y="52" textAnchor="middle" className="arch-label">Backend</text>
        <text x="200" y="66" textAnchor="middle" className="arch-sublabel">Spring Boot</text>
        <text x="200" y="80" textAnchor="middle" className="arch-sublabel">Java 21</text>

        {/* Arrow: Backend -> Redis */}
        <line x1="200" y1="25" x2="200" y2="10"
              stroke={arrowColor('backend-to-redis')}
              strokeWidth={arrowWidth('backend-to-redis')} />
        <rect x="165" y="0" width="70" height="15" rx="4"
              fill={activeArrows['backend-to-redis'] ? '#ffab0033' : '#1a1a3a'}
              stroke={activeArrows['backend-to-redis'] ? '#ffab00' : '#333366'} strokeWidth="1.5" />
        <text x="200" y="11" textAnchor="middle" className="arch-sublabel-sm">Redis</text>

        {/* Arrow: Backend -> DB (below) */}
        <line x1="200" y1="95" x2="200" y2="110"
              stroke={arrowColor('backend-to-db')}
              strokeWidth={arrowWidth('backend-to-db')} />
        <rect x="165" y="108" width="70" height="15" rx="4"
              fill={activeArrows['backend-to-db'] ? '#8d6e6333' : '#1a1a3a'}
              stroke={activeArrows['backend-to-db'] ? '#8d6e63' : '#333366'} strokeWidth="1.5" />
        <text x="200" y="119" textAnchor="middle" className="arch-sublabel-sm">MySQL</text>

        {/* Arrow: Backend -> Clients (WebSocket) */}
        <line x1="250" y1="50" x2="310" y2="40"
              stroke={arrowColor('backend-to-clients')}
              strokeWidth={arrowWidth('backend-to-clients')} />
        <polygon points="305,35 315,40 305,45"
                 fill={arrowColor('backend-to-clients')} />

        {/* Arrow: Backend -> Clients (SSE) */}
        <line x1="250" y1="70" x2="310" y2="80"
              stroke={arrowColor('backend-to-clients')}
              strokeWidth={arrowWidth('backend-to-clients')} />
        <polygon points="305,75 315,80 305,85"
                 fill={arrowColor('backend-to-clients')} />

        {/* WS Clients box */}
        <rect x="315" y="20" width="85" height="35" {...boxStyle(activeArrows['backend-to-clients'])} />
        <text x="357" y="37" textAnchor="middle" className="arch-sublabel">WebSocket</text>
        <text x="357" y="49" textAnchor="middle" className="arch-sublabel-sm">
          {systemStatus?.wsClients ?? 0} clients
        </text>

        {/* SSE Clients box */}
        <rect x="315" y="65" width="85" height="35" {...boxStyle(activeArrows['backend-to-clients'])} />
        <text x="357" y="82" textAnchor="middle" className="arch-sublabel">SSE Panel</text>
        <text x="357" y="94" textAnchor="middle" className="arch-sublabel-sm">
          {systemStatus?.sseClients ?? 0} clients
        </text>

        {/* Browser icon */}
        <rect x="420" y="35" width="70" height="50" {...boxStyle(false)} />
        <text x="455" y="58" textAnchor="middle" className="arch-label">Browser</text>
        <text x="455" y="72" textAnchor="middle" className="arch-sublabel">React</text>

        {/* Arrow: WS/SSE -> Browser */}
        <line x1="400" y1="55" x2="420" y2="60" stroke="#333366" strokeWidth="1.5" />
        <line x1="400" y1="75" x2="420" y2="65" stroke="#333366" strokeWidth="1.5" />
      </svg>
    </div>
  );
}

export default SystemArchDiagram;
