import React, { useMemo, useState } from 'react';
import { Clock } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// How far back each "memory" looks, in whole months — 6 months, 1/3/10 years. Kept as a single
// months-back number (rather than separate months/years shapes) so one label function below can
// derive "N months ago" vs "N years ago" from it directly.
const MEMORY_OFFSETS_MONTHS = [6, 12, 36, 120];

function memoryLabel(messages, offsetMonths) {
  return offsetMonths % 12 === 0
    ? messages["memoryYearsAgo"](offsetMonths / 12)
    : messages["memoryMonthsAgo"](offsetMonths);
}

// Top-of-Library-page timeline: a "Memories" strip (this month, N months/years ago) plus a
// browse-by-month list — both driven by the same `months` index (see useMonthlyTimeline) so
// there's only ever one source of truth for "which months actually have photos". See TODO.md
// for why these two were designed to share this one component/index.
export default function LibraryTimeline({ months, onJump }) {
  const { messages, locale } = useLocale();
  const [expandedYear, setExpandedYear] = useState(null);

  const monthFormatter = useMemo(
    () => new Intl.DateTimeFormat(locale, { month: 'long' }),
    [locale]
  );

  const monthByKey = useMemo(() => {
    const map = new Map();
    months.forEach(m => map.set(`${m.year}-${m.month}`, m));
    return map;
  }, [months]);

  const memories = useMemo(() => {
    const now = new Date();
    const currentTotalMonths = now.getFullYear() * 12 + now.getMonth(); // getMonth() is 0-based
    return MEMORY_OFFSETS_MONTHS.map(offsetMonths => {
      const totalMonths = currentTotalMonths - offsetMonths;
      const year = Math.floor(totalMonths / 12);
      const month = (totalMonths % 12) + 1; // back to 1-based
      const bucket = monthByKey.get(`${year}-${month}`);
      return bucket ? { year, month, count: bucket.count, coverPhotoUrl: bucket.coverPhotoUrl, offsetMonths } : null;
    }).filter(Boolean);
  }, [monthByKey]);

  const years = useMemo(() => {
    const map = new Map();
    months.forEach(m => {
      if (!map.has(m.year)) map.set(m.year, []);
      map.get(m.year).push(m);
    });
    return Array.from(map.entries())
      .sort((a, b) => b[0] - a[0])
      .map(([year, monthsInYear]) => [
        year,
        [...monthsInYear].sort((a, b) => b.month - a.month),
      ]);
  }, [months]);

  if (months.length === 0) return null; // nothing loaded yet, or a genuinely empty library

  return (
    <div className="library-timeline">
      {memories.length > 0 && (
        <div className="memories-strip">
          {memories.map(mem => (
            <button
              key={mem.offsetMonths}
              type="button"
              className="memory-card"
              onClick={() => onJump(mem.year, mem.month)}
            >
              {mem.coverPhotoUrl && <img src={mem.coverPhotoUrl} alt="" className="memory-card-thumb" loading="lazy" />}
              <div className="memory-card-caption">
                <span className="memory-card-label"><Clock size={12} className="memory-card-icon" /> {memoryLabel(messages, mem.offsetMonths)}</span>
                <span className="memory-card-date">{monthFormatter.format(new Date(mem.year, mem.month - 1, 1))} {mem.year} · {messages["photosCount"](mem.count)}</span>
              </div>
            </button>
          ))}
        </div>
      )}

      <div className="timeline-browse">
        <span className="timeline-browse-label">{messages["browseByMonth"]}</span>
        <div className="timeline-year-list">
          {years.map(([year, monthsInYear]) => (
            <div key={year} className="timeline-year">
              <button
                type="button"
                className={`timeline-year-btn ${expandedYear === year ? 'active' : ''}`}
                onClick={() => setExpandedYear(y => (y === year ? null : year))}
              >
                {year}
              </button>
              {expandedYear === year && (
                <div className="timeline-month-list">
                  {monthsInYear.map(m => (
                    <button
                      key={m.month}
                      type="button"
                      className="timeline-month-btn"
                      onClick={() => onJump(m.year, m.month)}
                    >
                      {monthFormatter.format(new Date(m.year, m.month - 1, 1))}
                      <span className="timeline-month-count">{m.count}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
