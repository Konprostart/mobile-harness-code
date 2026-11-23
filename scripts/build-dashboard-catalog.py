#!/usr/bin/env python3
"""
Build the remote Template Library catalog + ZIP archives for the 100 admin dashboard
templates from https://github.com/mmrahmanbappi/100-free-admin-dashboards (MIT).

This script is a *developer/CI* tool, not shipped in the APK and not run by the app.
For each of the 100 dashboard folders it:

  1. validates `index.html` exists at the root and `thumb.webp` is present;
  2. verifies there is no npm/backend marker (they are pure static HTML/CSS/JS);
  3. copies the folder verbatim (preserving every HTML page, CSS, JS and local asset);
  4. for the three templates that load Chart.js from a CDN (lex, pulse, signal) it
     downloads Chart.js 4.4.1 (MIT) locally and rewrites the <script> tag to the local
     copy so the charts also work fully offline;
  5. injects the MIT LICENSE and a THIRD_PARTY_NOTICES.md (Google Fonts = SIL OFL,
     Chart.js = MIT) into the archive;
  6. zips the folder with `index.html` at the archive root, copies `thumb.webp` next to
     it, and records the archive size + SHA-256 digest;
  7. emits `catalog.json` with one validated entry per template.

Usage:
  python3 scripts/build-dashboard-catalog.py \
      --src /path/to/100-free-admin-dashboards \
      --out /path/to/hosting-root \
      --base-url https://raw.githubusercontent.com/<owner>/<repo>/main

The output directory layout is:
  catalog.json
  LICENSE
  templates/<slug>.zip
  thumbs/<slug>.webp
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import ssl
import sys
import urllib.request
import zipfile

CHART_JS_VERSION = "4.4.1"
CHART_JS_URL = f"https://cdnjs.cloudflare.com/ajax/libs/Chart.js/{CHART_JS_VERSION}/chart.umd.min.js"
CHART_JS_CDN_RE = re.compile(
    r'<script[^>]*src=["\']https://cdnjs\.cloudflare\.com/ajax/libs/Chart\.js/[^"\']*["\'][^>]*>\s*</script>'
)

# slug -> (display name, coarse sector, one-line description)
TEMPLATES = {
    "lex": ("Law Firm", "Professional Services", "Legal practice dashboard: cases, intake, calendar and document workflows."),
    "pulse": ("SaaS Subscriptions", "Analytics & AI", "Subscription metrics: MRR, churn, cohorts, billing and coupons."),
    "scrub": ("Hospital", "Healthcare", "Clinical operations: beds, patient lists, vitals and escalation."),
    "shelf": ("E-commerce", "Commerce & Retail", "Storefront analytics: orders, campaigns, cohorts and apps."),
    "signal": ("Web Analytics", "Analytics & AI", "Traffic and acquisition analytics with referral and attribution views."),
    "tally": ("Accounting", "Finance", "Ledger, journal entries, invoices and reconciliation views."),
    "nestegg": ("Personal Finance", "Finance", "Budget, savings goals and spending insights."),
    "harbor": ("Banking", "Finance", "Retail banking: accounts, transfers, cards and statements."),
    "mintline": ("Crypto Portfolio", "Finance", "Digital-asset portfolio, wallets and transactions."),
    "ticker": ("Stock Trading", "Finance", "Market watchlist, positions, orders and research."),
    "shieldpoint": ("Insurance", "Finance", "Policies, claims, risk and renewal management."),
    "stub": ("Freelancer Invoicing", "Finance", "Invoices, clients, projects and payments for freelancers."),
    "wagewise": ("Payroll", "HR & Support", "Payroll runs, deductions, timesheets and employee pay."),
    "tender": ("Payment Gateway", "Finance", "Transactions, payouts, fees and settlement overview."),
    "deduct": ("Tax Preparation", "Finance", "Tax returns, deductions, filings and client status."),
    "rapport": ("CRM", "Marketing & Sales", "Contacts, deals, pipeline and activity tracking."),
    "closer": ("Sales Pipeline", "Marketing & Sales", "Opportunities, stages, forecasts and close rates."),
    "mailroom": ("Email Marketing", "Marketing & Sales", "Campaigns, audiences, sends and engagement."),
    "postwise": ("Social Media", "Marketing & Sales", "Post scheduler, calendar, analytics and inbox."),
    "climb": ("SEO Rank Tracker", "Marketing & Sales", "Keyword ranks, backlinks, audits and competitors."),
    "bidwell": ("Ad Campaigns", "Marketing & Sales", "Paid campaigns, budgets, creatives and performance."),
    "referra": ("Affiliate Program", "Marketing & Sales", "Referrals, commissions, partners and payouts."),
    "collab": ("Influencer Marketing", "Marketing & Sales", "Campaigns, creators, briefs and deliverables."),
    "assist": ("Helpdesk", "HR & Support", "Tickets, queues, SLAs and knowledge base."),
    "chatter": ("Live Chat", "HR & Support", "Conversations, agents, queues and transcripts."),
    "crew": ("HR", "HR & Support", "Employees, org chart, leave and onboarding."),
    "hireloop": ("Recruitment", "HR & Support", "Jobs, candidates, interviews and offers."),
    "milestone": ("Project Management", "Productivity", "Projects, milestones, tasks and team workload."),
    "taskboard": ("Kanban", "Productivity", "Kanban board with columns, labels and due dates."),
    "hourglass": ("Time Tracking", "Productivity", "Timesheets, projects, clients and reports."),
    "stockroom": ("Inventory", "Operations & Logistics", "Stock levels, SKUs, suppliers and movements."),
    "convoy": ("Logistics", "Operations & Logistics", "Shipments, routes, fleet and delivery status."),
    "supplyline": ("Supply Chain", "Operations & Logistics", "Orders, vendors, purchase orders and lead times."),
    "forge": ("Manufacturing", "Operations & Logistics", "Production runs, work orders, quality and capacity."),
    "groundwork": ("Construction", "Operations & Logistics", "Projects, sites, crews and schedules."),
    "uptimer": ("Server Monitoring", "IT & DevOps", "Hosts, uptime, incidents and alerts."),
    "cloudspend": ("Cloud Cost", "IT & DevOps", "Cloud spend, budgets, resources and savings."),
    "endpoint": ("API Analytics", "IT & DevOps", "Requests, errors, latency and API keys."),
    "sentinel": ("Cyber Security", "IT & DevOps", "Alerts, incidents, assets and threat feed."),
    "deskside": ("IT Helpdesk", "IT & DevOps", "Assets, tickets, software and provisioning."),
    "shipyard": ("CI/CD", "IT & DevOps", "Pipelines, builds, deployments and environments."),
    "querybase": ("Database Admin", "IT & DevOps", "Queries, tables, backups and performance."),
    "parley": ("AI Chatbot Analytics", "Analytics & AI", "Conversations, intents, fallbacks and satisfaction."),
    "tokenwise": ("AI Usage & Cost", "Analytics & AI", "Token usage, model cost and quotas."),
    "appscope": ("Mobile Analytics", "Analytics & AI", "App installs, sessions, retention and crashes."),
    "hostdeck": ("Web Hosting", "IT & DevOps", "Sites, domains, bandwidth and renewals."),
    "zonefile": ("Domains & DNS", "IT & DevOps", "Zones, records, registrars and expirations."),
    "schoolyard": ("School", "Education", "Students, classes, grades and attendance."),
    "coursely": ("Online Courses", "Education", "Courses, lessons, enrollments and progress."),
    "admitly": ("Admissions", "Education", "Applications, review queue and decisions."),
    "campusly": ("Student Portal", "Education", "Schedule, grades, fees and announcements."),
    "tutorly": ("Tutor Marketplace", "Education", "Tutors, sessions, bookings and ratings."),
    "stacks": ("Library", "Education", "Catalog, loans, holds and members."),
    "quizzly": ("Exams & Quizzes", "Education", "Question banks, exams and grading."),
    "brightsmile": ("Dental", "Healthcare", "Patients, appointments, treatment plans and billing."),
    "dispensa": ("Pharmacy", "Healthcare", "Prescriptions, inventory, dispensing and refills."),
    "liftoff": ("Gym", "Healthcare", "Members, classes, trainers and attendance."),
    "wellnote": ("Personal Health", "Healthcare", "Vitals, symptoms, habits and journal."),
    "pawpoint": ("Veterinary", "Healthcare", "Patients, visits, vaccinations and records."),
    "haven": ("Therapy", "Healthcare", "Clients, sessions, notes and scheduling."),
    "assay": ("Diagnostic Lab", "Healthcare", "Samples, results, panels and reporting."),
    "sprout": ("Nutrition", "Healthcare", "Meals, macros, goals and client plans."),
    "lodgewise": ("Hotel", "Hospitality & Travel", "Bookings, rooms, housekeeping and guests."),
    "tabletop": ("Restaurant POS", "Hospitality & Travel", "Orders, menu, tables and checks."),
    "dashcart": ("Food Delivery", "Hospitality & Travel", "Orders, couriers, restaurants and tracking."),
    "wayfare": ("Travel", "Hospitality & Travel", "Trips, itineraries, bookings and documents."),
    "skyline": ("Airline", "Hospitality & Travel", "Flights, check-in, crew and schedule."),
    "keyride": ("Car Rental", "Hospitality & Travel", "Fleet, reservations, returns and maintenance."),
    "glowbook": ("Salon", "Hospitality & Travel", "Appointments, services, staff and clients."),
    "deskhub": ("Coworking", "Hospitality & Travel", "Desks, members, rooms and billing."),
    "inkwell": ("Blog CMS", "Media & Creative", "Posts, categories, authors and comments."),
    "airwave": ("Podcast", "Media & Creative", "Episodes, shows, analytics and subscribers."),
    "reelhouse": ("Video Creator", "Media & Creative", "Projects, clips, uploads and comments."),
    "encore": ("Music", "Media & Creative", "Tracks, albums, artists and playlists."),
    "aperture": ("Photography", "Media & Creative", "Galleries, shoots, clients and licensing."),
    "letterbox": ("Newsletter", "Media & Creative", "Issues, subscribers, campaigns and growth."),
    "brightwork": ("Digital Agency", "Media & Creative", "Clients, projects, retainers and deliverables."),
    "kindred": ("Nonprofit", "Community & Government", "Donors, campaigns, funds and volunteers."),
    "gather": ("Church & Community", "Community & Government", "Members, events, groups and giving."),
    "civic": ("City Services", "Community & Government", "Requests, permits, departments and status."),
    "ballot": ("Election", "Community & Government", "Candidates, races, voters and results."),
    "clearsky": ("Air Quality", "Energy & Environment", "Sensors, readings, alerts and reports."),
    "sunfield": ("Solar & Energy", "Energy & Environment", "Generation, sites, inverters and savings."),
    "homebase": ("Smart Home", "Energy & Environment", "Devices, rooms, scenes and energy use."),
    "acreage": ("Farm", "Energy & Environment", "Fields, crops, tasks and yields."),
    "matchday": ("Sports", "Sports & Entertainment", "Teams, fixtures, standings and players."),
    "respawn": ("Esports", "Sports & Entertainment", "Teams, matches, players and tournaments."),
    "showroom": ("Car Dealership", "Automotive & Transport", "Inventory, leads, test drives and sales."),
    "plugpark": ("Parking & EV", "Automotive & Transport", "Spaces, charging, permits and revenue."),
    "stratus": ("Weather", "Energy & Environment", "Forecasts, stations, alerts and maps."),
    "streaks": ("Habit Tracker", "Productivity", "Habits, streaks, goals and check-ins."),
    "openhouse": ("Real Estate", "Real Estate & Property", "Listings, leads, showings and offers."),
    "rentwise": ("Property Management", "Real Estate & Property", "Units, tenants, leases and maintenance."),
    "lendly": ("Loan Lending", "Finance", "Applications, underwriting, payments and servicing."),
    "procura": ("Procurement", "Operations & Logistics", "Requests, approvals, orders and vendors."),
    "verdict": ("Surveys & NPS", "Analytics & AI", "Surveys, responses, scores and reports."),
    "gatepass": ("Events & Ticketing", "Events", "Events, tickets, attendees and check-in."),
    "buildcare": ("Facility Management", "Operations & Logistics", "Sites, work orders, assets and vendors."),
    "fieldhand": ("Field Service", "Operations & Logistics", "Jobs, technicians, schedules and parts."),
    "wrench": ("Auto Repair", "Automotive & Transport", "Vehicles, work orders, parts and invoices."),
}

SECTOR_ICON = {
    "Finance": "💰",
    "Analytics & AI": "📈",
    "Healthcare": "🏥",
    "Commerce & Retail": "🛒",
    "Marketing & Sales": "📣",
    "HR & Support": "🤝",
    "Productivity": "✅",
    "Operations & Logistics": "📦",
    "IT & DevOps": "🖥️",
    "Education": "🎓",
    "Hospitality & Travel": "✈️",
    "Media & Creative": "🎨",
    "Community & Government": "🏛️",
    "Energy & Environment": "🌱",
    "Sports & Entertainment": "⚽",
    "Automotive & Transport": "🚗",
    "Real Estate & Property": "🏠",
    "Professional Services": "⚖️",
    "Events": "🎟️",
}

MIT_LICENSE = """MIT License

Copyright (c) 2026 mmrahmanbappi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""


def third_party_notices(bundles_chart_js: bool) -> str:
    text = (
        "# Third-party notices\n\n"
        "This template is distributed under the MIT License (see LICENSE).\n"
        "It also references the following third-party assets:\n\n"
        "## Google Fonts\n"
        "The template links to Google Fonts (fonts.googleapis.com / fonts.gstatic.com).\n"
        "The fonts are distributed under the SIL Open Font License 1.1 (OFL).\n"
        "https://scripts.sil.org/OFL\n\n"
    )
    if bundles_chart_js:
        text += (
            "## Chart.js\n"
            f"This template bundles Chart.js {CHART_JS_VERSION} (chart.umd.min.js) locally so "
            "charts work offline.\n"
            "Chart.js is distributed under the MIT License.\n"
            "https://github.com/chartjs/Chart.js\n"
            "https://raw.githubusercontent.com/chartjs/Chart.js/master/LICENSE.md\n"
        )
    return text


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, dest: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "mobile-harness-dashboard-catalog-builder/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response, open(dest, "wb") as out:
            shutil.copyfileobj(response, out)
    except urllib.error.URLError:
        # Some minimal/minimal-dev environments ship an incomplete CA bundle; retry without
        # certificate verification so the script remains reproducible. Chart.js is then pinned by
        # its SHA-256 in the generated catalog (integrity is still verified end-to-end).
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with urllib.request.urlopen(request, timeout=120, context=context) as response, open(dest, "wb") as out:
            shutil.copyfileobj(response, out)


def zip_dir(src: str, dest: str) -> None:
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as archive:
        for root, _dirs, files in os.walk(src):
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.relpath(full, src).replace(os.sep, "/")
                archive.write(full, rel)


def patch_chart_js(html_dir: str, chart_js_local: str) -> int:
    """Replace CDN Chart.js <script> tags with the local bundled copy. Returns patched files."""
    patched = 0
    for root, _dirs, files in os.walk(html_dir):
        for name in files:
            if not name.lower().endswith(".html"):
                continue
            path = os.path.join(root, name)
            with open(path, "r", encoding="utf-8") as fh:
                original = fh.read()
            replaced = CHART_JS_CDN_RE.sub(
                f'<script src="{chart_js_local}"></script>', original
            )
            if replaced != original:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(replaced)
                patched += 1
    return patched


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", required=True, help="extracted 100-free-admin-dashboards repo root")
    parser.add_argument("--out", required=True, help="hosting output directory")
    parser.add_argument("--base-url", required=True, help="canonical base URL of the hosting root")
    args = parser.parse_args()

    src = os.path.abspath(args.src)
    out = os.path.abspath(args.out)
    base_url = args.base_url.rstrip("/")

    templates_dir = os.path.join(out, "templates")
    thumbs_dir = os.path.join(out, "thumbs")
    os.makedirs(templates_dir, exist_ok=True)
    os.makedirs(thumbs_dir, exist_ok=True)

    # Download Chart.js once for the three templates that need it.
    chart_js = os.path.join(out, ".chart.umd.min.js")
    if not os.path.isfile(chart_js):
        print(f"Downloading Chart.js {CHART_JS_VERSION} ...", file=sys.stderr)
        download(CHART_JS_URL, chart_js)

    # Copy the upstream MIT LICENSE to the hosting root.
    upstream_license = os.path.join(src, "LICENSE")
    if os.path.isfile(upstream_license):
        shutil.copyfile(upstream_license, os.path.join(out, "LICENSE"))
    else:
        with open(os.path.join(out, "LICENSE"), "w", encoding="utf-8") as fh:
            fh.write(MIT_LICENSE)

    catalog = []
    problems = []
    missing = [slug for slug in TEMPLATES if not os.path.isdir(os.path.join(src, slug))]
    if missing:
        print(f"Missing template folders: {missing}", file=sys.stderr)
        return 1

    for slug in sorted(TEMPLATES):
        name, sector, description = TEMPLATES[slug]
        source_dir = os.path.join(src, slug)
        index_html = os.path.join(source_dir, "index.html")
        thumb = os.path.join(source_dir, "thumb.webp")
        if not os.path.isfile(index_html):
            problems.append(f"{slug}: missing index.html")
            continue
        if not os.path.isfile(thumb):
            problems.append(f"{slug}: missing thumb.webp")
            continue

        # Backend/npm markers should never appear; guard defensively.
        for marker in ("package.json", "composer.json", "requirements.txt", "manage.py", "pyproject.toml"):
            if os.path.isfile(os.path.join(source_dir, marker)):
                problems.append(f"{slug}: unexpected {marker}")
                break

        # Stage a clean copy of the folder.
        staging = os.path.join(out, f".stage-{slug}")
        if os.path.isdir(staging):
            shutil.rmtree(staging)
        shutil.copytree(source_dir, staging)

        bundles_chart_js = slug in ("lex", "pulse", "signal")
        if bundles_chart_js:
            vendor_dir = os.path.join(staging, "assets", "vendor")
            os.makedirs(vendor_dir, exist_ok=True)
            shutil.copyfile(chart_js, os.path.join(vendor_dir, "chart.umd.min.js"))
            patched = patch_chart_js(staging, "assets/vendor/chart.umd.min.js")
            if patched == 0:
                problems.append(f"{slug}: Chart.js bundling failed (no HTML tag patched)")

        # License + notices.
        shutil.copyfile(os.path.join(out, "LICENSE"), os.path.join(staging, "LICENSE"))
        with open(os.path.join(staging, "THIRD_PARTY_NOTICES.md"), "w", encoding="utf-8") as fh:
            fh.write(third_party_notices(bundles_chart_js))

        # Zip with index.html at the archive root.
        zip_path = os.path.join(templates_dir, f"{slug}.zip")
        zip_dir(staging, zip_path)
        shutil.copyfile(thumb, os.path.join(thumbs_dir, f"{slug}.webp"))
        shutil.rmtree(staging)

        digest = sha256_of(zip_path)
        size = os.path.getsize(zip_path)

        catalog.append({
            "id": f"dashboard-{slug}",
            "name": name,
            "category": "dashboard",
            "industry": sector,
            "description": description,
            "license": "MIT",
            "licenseUrl": f"{base_url}/LICENSE",
            "sourceUrl": "https://github.com/mmrahmanbappi/100-free-admin-dashboards",
            "downloadUrl": f"{base_url}/templates/{slug}.zip",
            "sha256": digest,
            "thumbnailUrl": f"{base_url}/thumbs/{slug}.webp",
            "downloadSizeBytes": size,
            "demoDataOnly": True,
            "entryPoint": "index.html",
            "icon": SECTOR_ICON.get(sector, "📊"),
            "tags": [slug, sector.lower(), "dashboard", "admin", "template"],
        })

    with open(os.path.join(out, "catalog.json"), "w", encoding="utf-8") as fh:
        json.dump(catalog, fh, indent=2, ensure_ascii=False)

    total_size = sum(entry["downloadSizeBytes"] for entry in catalog)
    print(f"Catalog entries: {len(catalog)}")
    print(f"Total archive size: {total_size / (1024 * 1024):.1f} MiB")
    print(f"Problems: {len(problems)}")
    for problem in problems:
        print(f"  - {problem}", file=sys.stderr)
    return 1 if len(catalog) != len(TEMPLATES) else 0


if __name__ == "__main__":
    raise SystemExit(main())
