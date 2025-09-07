MigraineMe
An Android app built with Jetpack Compose and Supabase backend to help migraine sufferers understand, predict, and manage their condition.

🌍 Vision
The goal of MigraineMe is to compete with existing migraine tracker apps — but with one critical difference: AI-driven insights and passive data collection.

Where most migraine trackers require constant manual input, MigraineMe aims to minimize user burden. We want to give patients a clear, personalized view of what impacts their migraines, while asking them to log as little as possible.

✨ Key Differentiators
AI-First

Model how triggers, reliefs, and medicines affect the patient.
Use the “bucket theory”: migraines occur when combined triggers overflow a threshold.
Dynamically calculate daily migraine risk from multiple interacting factors.
Passive Data Integration

Pull health and lifestyle signals automatically from sources like Whoop, wearables, or other trackers.
Incorporate weather data and environmental conditions.
Reduce manual inputs to a minimum.
Real-World Impact Measurement

Track how medicines and relief strategies actually affect the user’s physiology (e.g., HRV).
Provide evidence-backed recommendations based on passive data and historical logs.
🧩 Core Features (Current App)
Logging: Headaches, severity, notes, type, medicines, reliefs.
Live Risk Score: A dynamic measure of migraine likelihood based on recent and ongoing logs.
Trends: 14-day view of severity and frequency.
Customization: Manage triggers, medicines, reliefs, and impacts.
Supabase Integration: Authentication (GoTrue), PostgREST data persistence.
🛠️ Tech Stack
Frontend: Android (Kotlin, Jetpack Compose, Material3)
Backend: Supabase (PostgREST, GoTrue Auth, Functions)
State Management: In-app stores (MigraineLogStore, CatalogStore)
🚀 Current Focus
Stable Gradle setup (to make builds “just work”).
Simplify logging workflow.
Keep UI clean and focused (minimal top bars, modern Compose-first look).
Expand risk modeling with real-time data sources.
🔮 Roadmap
 Integrate external health trackers (Whoop, Fitbit, etc.).
 Weather + environmental data correlation.
 AI trigger modeling & “bucket theory” simulation.
 Passive measurement of medicine/relief effectiveness (e.g., via HRV).
 Personalized daily migraine risk prediction.
🤝 Contribution
This is an early-stage, experimental project.
Contributions are welcome — especially around:

Data modeling & AI
Wearable integrations
UX simplification
📜 License
TBD (default: private development)

repo: https://github.com/jverwerf/MigraineMe
supa: uuid: f04ac70c-4bb0-4d44-b392-328dd0f3ffab
supa: anon eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InF5a2ZsYXJwaWJvZnZmZm16Z2hpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTY0NjU5NDMsImV4cCI6MjA3MjA0MTk0M30.r3DHA2EKNvC_AraPs1gwgaBl_oEBpDrD1bwPfiuiSbM
