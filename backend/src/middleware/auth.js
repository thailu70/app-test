const jwt = require('jsonwebtoken');

function resolveJwtSecret() {
  if (process.env.JWT_SECRET && process.env.JWT_SECRET.trim().length > 0) {
    return process.env.JWT_SECRET.trim();
  }
  if (process.env.NODE_ENV === 'production') {
    throw new Error('[FATAL SECURITY ERROR] JWT_SECRET environment variable is missing in production environment. A secure key must be provided.');
  }
  return 'transport_navigator_ethiopia_secret_key_vps_2026';
}

const JWT_SECRET = resolveJwtSecret();
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '30d';

function signToken(payload) {
  return jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_EXPIRES_IN });
}

function verifyToken(token) {
  if (!token) return null;
  try {
    return jwt.verify(token, JWT_SECRET);
  } catch (err) {
    return null;
  }
}

/**
 * Authentication Middleware: Extracts and validates Bearer token
 */
function authenticate(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({
      success: false,
      error: 'Authentication required. Please provide a valid Bearer token.'
    });
  }

  const token = authHeader.split(' ')[1];
  const decoded = verifyToken(token);

  if (!decoded) {
    return res.status(401).json({
      success: false,
      error: 'Invalid or expired authentication token.'
    });
  }

  req.user = decoded;
  next();
}

/**
 * Role Isolation Guard: Ensures the authenticated user has one of the allowed roles
 */
function requireRole(...allowedRoles) {
  return (req, res, next) => {
    if (!req.user || !req.user.role) {
      return res.status(401).json({
        success: false,
        error: 'Authentication required.'
      });
    }

    const userRole = req.user.role.toUpperCase();
    const normalizedAllowed = allowedRoles.map(r => r.toUpperCase());

    if (!normalizedAllowed.includes(userRole)) {
      return res.status(403).json({
        success: false,
        error: `Forbidden: Access restricted. Required role: [${allowedRoles.join(', ')}], current role: ${req.user.role}`
      });
    }

    next();
  };
}

module.exports = {
  JWT_SECRET,
  signToken,
  verifyToken,
  authenticate,
  requireRole
};
