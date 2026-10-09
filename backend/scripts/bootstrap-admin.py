#!/usr/bin/env python3
"""Create the first RoutePass administrator using the server's one-time bootstrap secret.

Run on the VPS as root: python3 /var/www/routepass/scripts/bootstrap-admin.py
Reads ADMIN_REGISTRATION_SECRET from /var/www/routepass/.env, prompts privately for the
password, and never prints the secret, password, or returned JWT.
"""
import getpass
import json
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ENV_PATH = Path("/var/www/routepass/.env")
DEFAULT_API = "https://routepass.duckdns.org"


def read_env(path):
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def main():
    if not ENV_PATH.is_file():
        print(f"Environment file not found: {ENV_PATH}", file=sys.stderr)
        return 1
    env = read_env(ENV_PATH)
    secret = env.get("ADMIN_REGISTRATION_SECRET", "")
    if len(secret) < 32:
        print("ADMIN_REGISTRATION_SECRET is missing or too short in the VPS .env file.", file=sys.stderr)
        return 1

    default_api = env.get("ROUTEPASS_DOMAIN", "").strip()
    default_api = "https://" + default_api if default_api else DEFAULT_API
    api_base = input(f"RoutePass HTTPS API [{default_api}]: ").strip() or default_api
    parsed = urlparse(api_base)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        print("Use a valid HTTPS URL, such as https://routepass.duckdns.org", file=sys.stderr)
        return 1
    api_base = api_base.rstrip("/")
    if parsed.path not in ("", "/"):
        print("Enter the site root only; the script adds /api/auth/register.", file=sys.stderr)
        return 1

    full_name = input("Administrator full name: ").strip()
    phone = input("Administrator phone number (this is the login username): ").strip()
    email = input("Administrator email (optional): ").strip()
    company = input("Organization name (optional): ").strip()
    if not full_name or not phone:
        print("Full name and phone number are required.", file=sys.stderr)
        return 1
    password = getpass.getpass("Choose admin password (minimum 14 characters): ")
    confirm = getpass.getpass("Confirm admin password: ")
    if len(password) < 14:
        print("Choose a password with at least 14 characters.", file=sys.stderr)
        return 1
    if password != confirm:
        print("Passwords did not match.", file=sys.stderr)
        return 1

    payload = {
        "fullName": full_name,
        "phone": phone,
        "email": email,
        "companyName": company,
        "password": password,
        "role": "ADMIN",
        "adminSecret": secret,
    }
    request = Request(
        api_base + "/api/auth/register",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=20) as response:
            body = json.loads(response.read().decode("utf-8"))
            if not body.get("success"):
                print("Admin creation was not confirmed by the server.", file=sys.stderr)
                return 1
            user = body.get("user") or {}
            print("\nAdministrator account created successfully.")
            print("Login URL: " + api_base + "/admin/")
            print("Login username (phone): " + str(user.get("phone", phone)))
            print("Password: the one you just entered (not displayed or saved by this script).")
            print("Sign in now, then keep your credentials private.")
            return 0
    except HTTPError as exc:
        try:
            error = json.loads(exc.read().decode("utf-8"))
            message = str(error.get("error", "HTTP request rejected."))
        except Exception:
            message = "HTTP request rejected."
        if exc.code == 403 and "administrator already exists" in message.lower():
            print("An administrator already exists. Use that account to sign in; this script will not reset it.", file=sys.stderr)
        else:
            print(f"Admin creation failed (HTTP {exc.code}): {message}", file=sys.stderr)
        return 1
    except (URLError, TimeoutError) as exc:
        print(f"Could not reach RoutePass over HTTPS: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Admin creation failed: {type(exc).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
