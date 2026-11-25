(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.PassoEthiopianDateTime = api;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';

  const ZONE = 'Africa/Addis_Ababa';
  const dateFormatter = new Intl.DateTimeFormat('en-ET-u-ca-ethiopic', {
    timeZone: ZONE, calendar: 'ethiopic', dateStyle: 'medium'
  });
  const hourFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE, hour: '2-digit', hourCycle: 'h23'
  });
  const minuteFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE, minute: '2-digit'
  });

  function asDate(value) {
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
    if (typeof value === 'number' && Number.isFinite(value)) {
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? null : date;
    }
    const raw = String(value ?? '').trim();
    if (!raw) return null;
    if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
      const [year, month, day] = raw.split('-').map(Number);
      const date = new Date(Date.UTC(year, month - 1, day, 12));
      if (Number.isNaN(date.getTime()) || date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
      return date;
    }
    const date = new Date(raw);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function label(ethiopianHour) {
    if (ethiopianHour <= 11) return 'Day · ቀን';
    if (ethiopianHour <= 17) return 'Evening · ማታ';
    return 'Night · ሌሊት';
  }

  function clockFromEAT(hour, minute) {
    const ethiopianHour = ((hour - 6) % 24 + 24) % 24;
    return String(ethiopianHour % 12 || 12).padStart(2, '0') + ':' +
      String(minute).padStart(2, '0') + ' · ' + label(ethiopianHour);
  }

  function formatDate(value) {
    const raw = String(value ?? '').trim();
    const date = asDate(value);
    if (!date) return raw || '—';
    const ethiopianDay = /^\d{4}-\d{2}-\d{2}$/.test(raw)
      ? date
      : new Date(date.getTime() - 6 * 60 * 60 * 1000);
    return dateFormatter.format(ethiopianDay);
  }

  function formatClock(value) {
    const raw = String(value ?? '').trim();
    const time = /^(\d{1,2}):(\d{2})$/.exec(raw);
    if (time) {
      const hour = Number(time[1]), minute = Number(time[2]);
      if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return raw;
      return clockFromEAT(hour, minute);
    }
    const date = asDate(value);
    if (!date) return raw || '—';
    const eatHour = Number(hourFormatter.format(date));
    const minute = Number(minuteFormatter.format(date));
    return clockFromEAT(eatHour, minute);
  }

  function formatTimestamp(value) {
    const raw = String(value ?? '').trim();
    if (!raw) return '—';
    if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return formatDate(raw);
    const date = asDate(value);
    if (!date) return raw;
    return formatDate(date) + ' · ' + formatClock(date);
  }

  function formatUpdated(value) {
    const formatted = formatClock(value);
    return formatted === '—' ? '—' : 'Updated ' + formatted;
  }

  return Object.freeze({ formatDate, formatClock, formatTimestamp, formatUpdated });
});
