import { useState } from 'react';

function PollCycleGroup({ group, isLatest, typeColors, typeIcons }) {
  const [expanded, setExpanded] = useState(isLatest);

  // Extract summary info from steps
  const endStep = group.steps.find(s => s.type === 'POLL_CYCLE_END');
  const totalMs = endStep?.durationMs || 0;
  const updates = endStep?.metadata?.Updates || '0';
  const apisPolled = endStep?.metadata?.APIs || '0';

  return (
    <div className="poll-cycle-group">
      <div className="poll-cycle-header" onClick={() => setExpanded(!expanded)}>
        <span className="poll-expand">{expanded ? '▼' : '▶'}</span>
        <span className="poll-badge">🔄 {group.label}</span>
        <span className="poll-summary">
          {totalMs}ms | {apisPolled} APIs | {updates} updates
        </span>
        <span className="poll-time">
          {new Date(group.timestamp).toLocaleTimeString()}
        </span>
      </div>

      {expanded && (
        <div className="poll-cycle-body">
          {group.steps.map((step, si) => (
            <PollStepRow
              key={si}
              step={step}
              isLast={si === group.steps.length - 1}
              typeColors={typeColors}
              typeIcons={typeIcons}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function PollStepRow({ step, isLast, typeColors, typeIcons }) {
  const color = typeColors[step.type] || '#8892b0';
  const icon = typeIcons[step.type] || '●';

  return (
    <div className="step-row compact">
      <div className="step-timeline">
        <div className="timeline-dot sm" style={{ borderColor: color, background: isLast ? color : 'transparent' }} />
        {!isLast && <div className="timeline-line" style={{ background: color + '40' }} />}
      </div>
      <div className="step-content compact">
        <div className="step-header">
          <span className="step-icon sm">{icon}</span>
          <span className="step-name sm" style={{ color }}>{step.name}</span>
          <span className="step-duration">{step.durationMs}ms</span>
        </div>
        <div className="step-detail sm">{step.detail}</div>
        {step.metadata && Object.keys(step.metadata).length > 0 && (
          <div className="step-metadata">
            {Object.entries(step.metadata).map(([key, value]) => (
              <span key={key} className="metadata-pill sm">
                <span className="metadata-key">{key}:</span> {value}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export default PollCycleGroup;
