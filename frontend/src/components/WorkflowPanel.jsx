import { useRef, useEffect, useState, useCallback } from 'react';
import PollCycleGroup from './PollCycleGroup';
import api from '../services/api';

const TYPE_COLORS = {
  API_GATEWAY: '#40c4ff',
  CACHE_CHECK: '#ffab00',
  CACHE_WRITE: '#ffab00',
  EXTERNAL_API: '#42a5f5',
  CIRCUIT_BREAKER: '#ef5350',
  RATE_LIMIT: '#ffa726',
  RESPONSE: '#00c853',
  ERROR: '#ff5252',
  POLL_CYCLE_START: '#5c6bc0',
  POLL_CYCLE_END: '#5c6bc0',
  API_QUOTA_CHECK: '#26a69a',
  DATA_DIFF: '#00bcd4',
  WEBSOCKET_FANOUT: '#7e57c2',
  AUTH_CHECK: '#78909c',
  DB_WRITE: '#8d6e63',
  DB_READ: '#8d6e63',
  SCHEDULER_TICK: '#c0ca33',
  API_SOURCE_SELECT: '#ec407a',
  SUBSCRIPTION_UPDATE: '#42a5f5',
  LLM_INFERENCE: '#a855f7',
};

const TYPE_ICONS = {
  API_GATEWAY: '🚪',
  CACHE_CHECK: '💾',
  CACHE_WRITE: '💾',
  EXTERNAL_API: '🌐',
  CIRCUIT_BREAKER: '⚡',
  RATE_LIMIT: '⏱️',
  RESPONSE: '✅',
  ERROR: '❌',
  POLL_CYCLE_START: '🔄',
  POLL_CYCLE_END: '🔄',
  API_QUOTA_CHECK: '📊',
  DATA_DIFF: '🔍',
  WEBSOCKET_FANOUT: '📡',
  AUTH_CHECK: '🔐',
  DB_WRITE: '💾',
  DB_READ: '💾',
  SCHEDULER_TICK: '⏰',
  API_SOURCE_SELECT: '🎯',
  SUBSCRIPTION_UPDATE: '🔔',
  LLM_INFERENCE: '🤖',
};

const TRACE_TYPE_COLORS = {
  POLL_CYCLE: '#5c6bc0',
  USER_ACTION: '#4caf50',
  SYSTEM_EVENT: '#ff9800',
};

const TRACE_TYPE_LABELS = {
  POLL_CYCLE: 'Poll Cycle',
  USER_ACTION: 'User Action',
  SYSTEM_EVENT: 'System Event',
};

function WorkflowPanel({ events, connected, systemStatus, onClear }) {
  const scrollRef = useRef(null);
  const [filters, setFilters] = useState({
    POLL_CYCLE: true,
    USER_ACTION: true,
    SYSTEM_EVENT: true,
  });
  const [countdown, setCountdown] = useState(30);
  const lastPollTimeRef = useRef(Date.now());
  const [narration, setNarration] = useState('');
  const [narratorLoading, setNarratorLoading] = useState(false);
  const [narratorOpen, setNarratorOpen] = useState(false);

  const requestNarration = useCallback(async () => {
    if (narratorLoading) return;
    const stepEvents = events.filter(e => e.eventType === 'WORKFLOW_STEP');
    if (stepEvents.length === 0) return;
    setNarratorLoading(true);
    try {
      const payload = stepEvents.slice(-15).map(e => ({
        type: e.type,
        name: e.name,
        detail: e.detail,
        durationMs: e.durationMs,
        cacheStatus: e.cacheStatus,
        metadata: e.metadata,
      }));
      const res = await api.post('/workflow/narrate', payload);
      if (res.data?.narration) {
        setNarration(res.data.narration);
      }
    } catch (err) {
      // silently fail
    } finally {
      setNarratorLoading(false);
    }
  }, [narratorLoading, events]);

  // Reset countdown when a new poll cycle starts
  useEffect(() => {
    const lastEvent = events[events.length - 1];
    if (lastEvent?.type === 'POLL_CYCLE_START' || lastEvent?.type === 'POLL_CYCLE_END') {
      lastPollTimeRef.current = Date.now();
      setCountdown(30);
    }
  }, [events]);

  // Tick the countdown every second
  useEffect(() => {
    const timer = setInterval(() => {
      const elapsed = Math.floor((Date.now() - lastPollTimeRef.current) / 1000);
      const remaining = Math.max(0, 30 - elapsed);
      setCountdown(remaining);
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [events]);

  // Group events by traceId
  const groupedTraces = [];
  let currentGroup = null;

  for (const event of events) {
    if (event.eventType === 'TRACE_START') {
      currentGroup = {
        traceId: event.traceId,
        traceType: event.traceType,
        label: event.label,
        timestamp: event.timestamp,
        steps: [],
      };
      groupedTraces.push(currentGroup);
    } else if (event.eventType === 'WORKFLOW_STEP') {
      if (!currentGroup || currentGroup.traceId !== event.traceId) {
        currentGroup = {
          traceId: event.traceId,
          traceType: event.traceType || 'USER_ACTION',
          label: event.name || '?',
          timestamp: event.timestamp,
          steps: [],
        };
        groupedTraces.push(currentGroup);
      }
      currentGroup.steps.push(event);
    }
  }

  // Filter traces
  const filteredTraces = groupedTraces.filter(g => filters[g.traceType] !== false);

  // Get last poll cycle number
  const lastPollCycle = groupedTraces.filter(g => g.traceType === 'POLL_CYCLE').length;

  const toggleFilter = (type) => {
    setFilters(prev => ({ ...prev, [type]: !prev[type] }));
  };

  return (
    <div className="workflow-panel">
      <div className="panel-header">
        <h3 className="panel-title">
          <span className="panel-icon">🔧</span>
          System Design Panel
        </h3>
        <span className="panel-subtitle">Live workflow visualization</span>
        <div className="panel-status">
          <span className={`connection-badge ${connected ? 'connected' : 'disconnected'}`}>
            {connected ? 'Connected' : 'Reconnecting...'}
          </span>
          {onClear && events.length > 0 && (
            <button className="clear-btn" onClick={onClear} title="Clear all events">
              Clear
            </button>
          )}
        </div>
      </div>

      <>
      {/* Live cycle counter */}
      <div className="cycle-counter">
        <span>Cycle #{systemStatus?.pollCycleNumber || lastPollCycle}</span>
        <span className="cycle-sep">|</span>
        <span className={`countdown ${countdown <= 5 ? 'countdown-soon' : ''}`}>
          Next fetch in {countdown}s
        </span>
        <span className="cycle-sep">|</span>
        <span>{systemStatus?.footballData?.remainingQuota ?? '?'}/{systemStatus?.footballData?.maxQuota ?? 10} API quota</span>
        <span className="cycle-sep">|</span>
        <span>{systemStatus?.wsClients ?? 0} WS + {systemStatus?.sseClients ?? 0} SSE clients</span>
      </div>

      {/* AI Narrator */}
      <div className="narrator-section">
        <button
          className={`narrator-toggle ${narratorOpen ? 'open' : ''}`}
          onClick={() => {
            const next = !narratorOpen;
            setNarratorOpen(next);
            if (next && !narration && !narratorLoading) {
              requestNarration();
            }
          }}
        >
          <span className="narrator-icon">&#129302;</span>
          <span>AI Explain</span>
          <span className="narrator-chevron">{narratorOpen ? '\u25BC' : '\u25B6'}</span>
        </button>
        {narratorOpen && (
          <div className="narrator-bar">
            {narratorLoading ? (
              <span className="narrator-text loading">Analyzing workflow...</span>
            ) : narration ? (
              <>
                <span className="narrator-text">{narration}</span>
                <button className="narrator-refresh" onClick={requestNarration} title="Refresh narration">&#8635;</button>
              </>
            ) : (
              <span className="narrator-text loading">Click to analyze recent events...</span>
            )}
          </div>
        )}
      </div>

      {/* Filter toggles */}
      <div className="panel-filters">
        {Object.entries(TRACE_TYPE_LABELS).map(([type, label]) => (
          <button
            key={type}
            className={`filter-btn ${filters[type] ? 'active' : ''}`}
            style={{ borderColor: TRACE_TYPE_COLORS[type], color: filters[type] ? '#fff' : TRACE_TYPE_COLORS[type], background: filters[type] ? TRACE_TYPE_COLORS[type] + '33' : 'transparent' }}
            onClick={() => toggleFilter(type)}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Legend */}
      <div className="panel-legend">
        {Object.entries({
          'Gateway': '#40c4ff',
          'Cache': '#ffab00',
          'API': '#42a5f5',
          'Auth': '#78909c',
          'DB': '#8d6e63',
          'Diff': '#00bcd4',
          'WebSocket': '#7e57c2',
          'Scheduler': '#c0ca33',
        }).map(([label, color]) => (
          <span key={label} className="legend-item">
            <span className="legend-dot" style={{ background: color }} />
            {label}
          </span>
        ))}
      </div>

      <div ref={scrollRef} className="panel-events">
        {filteredTraces.length === 0 ? (
          <div className="panel-empty">
            <p className="empty-icon">📡</p>
            <p>Waiting for events...</p>
            <p className="empty-hint">Interact with the dashboard to see the workflow</p>
          </div>
        ) : (
          filteredTraces.map((group, gi) => {
            if (group.traceType === 'POLL_CYCLE') {
              return (
                <PollCycleGroup
                  key={group.traceId}
                  group={group}
                  isLatest={gi === filteredTraces.length - 1}
                  typeColors={TYPE_COLORS}
                  typeIcons={TYPE_ICONS}
                />
              );
            }
            return (
              <TraceGroup
                key={group.traceId}
                group={group}
                typeColors={TYPE_COLORS}
                typeIcons={TYPE_ICONS}
              />
            );
          })
        )}
      </div>
      </>
    </div>
  );
}

function TraceGroup({ group, typeColors, typeIcons }) {
  const traceColor = TRACE_TYPE_COLORS[group.traceType] || '#4caf50';

  return (
    <div className="trace-group" style={{ borderLeftColor: traceColor }}>
      <div className="trace-header">
        <span className="trace-badge" style={{ background: traceColor + '33', color: traceColor }}>
          {TRACE_TYPE_LABELS[group.traceType] || group.traceType}
        </span>
        <span className="trace-label">{group.label}</span>
        <span className="trace-id">#{group.traceId}</span>
      </div>
      <div className="trace-divider" />
      {group.steps.map((step, si) => (
        <WorkflowStepRow
          key={si}
          step={step}
          isLast={si === group.steps.length - 1}
          typeColors={typeColors}
          typeIcons={typeIcons}
        />
      ))}
    </div>
  );
}

function WorkflowStepRow({ step, isLast, typeColors, typeIcons }) {
  const color = typeColors[step.type] || '#8892b0';
  const icon = typeIcons[step.type] || '●';
  const isCacheHit = step.cacheStatus === 'HIT';
  const isCacheMiss = step.cacheStatus === 'MISS';

  return (
    <div className="step-row">
      <div className="step-timeline">
        <div className="timeline-dot" style={{ borderColor: color, background: isLast ? color : 'transparent' }} />
        {!isLast && <div className="timeline-line" style={{ background: color + '40' }} />}
      </div>
      <div className="step-content">
        <div className="step-header">
          <span className="step-icon">{icon}</span>
          <span className="step-name" style={{ color }}>[{step.stepNumber}] {step.name}</span>
          {step.type === 'RESPONSE' && step.statusCode && (
            <span className="status-code-badge" style={{
              background: step.statusCode < 300 ? '#00c85322' : '#ff525222',
              color: step.statusCode < 300 ? '#00c853' : '#ff5252'
            }}>{step.statusCode}</span>
          )}
          <span className="step-duration">{step.durationMs}ms</span>
        </div>
        <div className="step-detail">{step.detail}</div>
        <div className="step-badges">
          {isCacheHit && <span className="badge cache-hit">HIT</span>}
          {isCacheMiss && <span className="badge cache-miss">MISS</span>}
          {step.resultCount != null && step.type === 'RESPONSE' && (
            <span className="badge result-count">{step.resultCount} results</span>
          )}
        </div>
        {step.metadata && Object.keys(step.metadata).length > 0 && (
          <div className="step-metadata">
            {Object.entries(step.metadata).map(([key, value]) => (
              <span key={key} className="metadata-pill">
                <span className="metadata-key">{key}:</span> {value}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export default WorkflowPanel;
