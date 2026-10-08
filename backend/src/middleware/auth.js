const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'transport_navigator_ethiopia_secret_key_vps_2026';
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '30d';

function signToken(payload) {
  return jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_EXPIRES_IN });
}

function verifyToken(token) {
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
    if (!req.user) {
      return res.status(401).json({
        success: false,
        error: 'Authentication required.'
      });
    }

    if (!allowedRoles.includes(req.user.role)) {
      return res.status(403).json({
        success: false,
        error: `Access denied. Role '${req.user.role}' is not authorized for this resource. Required role(s): [${allowedRoles.join(', ')}].`
      });
    }

    next();
  };
}

module.exports = {
  signToken,
  verifyToken,
  authenticate,
  requireRole
};
