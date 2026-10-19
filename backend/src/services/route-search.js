// Build parameterized search clauses shared by SQLite and PostgreSQL.
function routeSearch(query) {
  const text = (key) => {
    if (query[key] === undefined) return '';
    if (typeof query[key] !== 'string' || query[key].length > 120) {
      throw new Error(`${key} must be text of at most 120 characters.`);
    }
    return query[key].trim();
  };
  const clauses = ['r.active = TRUE'];
  const params = [];
  for (const key of ['q', 'origin', 'destination']) {
    const value = text(key).toLowerCase();
    if (!value) continue;
    // Treat wildcard characters literally rather than expanding the search.
    const pattern = '%' + value.replace(/[!%_]/g, '!$&') + '%';
    if (key === 'q') {
      clauses.push("(LOWER(r.name) LIKE ? ESCAPE '!' OR LOWER(r.nameAm) LIKE ? ESCAPE '!' OR LOWER(r.description) LIKE ? ESCAPE '!' OR LOWER(r.origin) LIKE ? ESCAPE '!' OR LOWER(r.destination) LIKE ? ESCAPE '!')");
      params.push(pattern, pattern, pattern, pattern, pattern);
    } else {
      clauses.push(`LOWER(r.${key}) LIKE ? ESCAPE '!'`);
      params.push(pattern);
    }
  }
  const type = text('routeType');
  if (type && !['DAILY_COMMUTE', 'INTERCITY'].includes(type)) throw new Error('Invalid routeType.');
  if (type) { clauses.push('r.routeType = ?'); params.push(type); }
  const period = text('period');
  if (period && !['morning', 'evening'].includes(period)) throw new Error('Invalid period.');
  if (period) clauses.push(`COALESCE(r.${period === 'morning' ? 'morningDeparture' : 'eveningDeparture'}, '') <> ''`);
  const sort = text('sort') || 'name';
  const orders = { name: 'r.name ASC, r.id ASC', fare: 'r.basePriceEtb ASC, r.name ASC, r.id ASC', distance: 'r.distanceKm ASC, r.name ASC, r.id ASC' };
  if (!Object.hasOwn(orders, sort)) throw new Error('Invalid sort.');
  return { where: clauses.join(' AND '), params, order: orders[sort] };
}
module.exports = { routeSearch };
