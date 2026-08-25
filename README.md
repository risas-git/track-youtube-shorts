# YouTube Shorts Tracker for Microsoft Edge (Android)

An Android application built in Java with Android Studio that monitors the **Microsoft Edge** mobile browser (`com.microsoft.emmx`), captures active YouTube Shorts URLs (`youtube.com/shorts/{videoId}`), calculates active watch duration, and automatically syncs session data to a remote **Supabase PostgreSQL** database via PostgREST REST API (OkHttp).

---

## Architecture Overview

```
 ┌────────────────────────┐
 │ Microsoft Edge Mobile  │
 │  (com.microsoft.emmx)  │
 └───────────┬────────────┘
             │ Accessibility Events (URL / Omnibox text)
             ▼
 ┌─────────────────────────────────────────────────────────┐
 │ EdgeShortsAccessibilityService                          │
 │  - Traverses UI tree & matches youtube.com/shorts/{id}  │
 │  - Debounced (300ms) event inspection                   │
 │  - Tracks start timestamp, active short ID & duration   │
 │  - Handles video change, app-switch, and screen locks   │
 │  - Filters brief skips (< 2s)                           │
 └───────────┬─────────────────────────────────────────────┘
             │ Completed ShortSession POJO
             ▼
 ┌─────────────────────────────────────────────────────────┐
 │ SupabaseClient (OkHttp REST Engine)                     │
 │  - POST /rest/v1/youtube_shorts_history                 │
 │  - Headers: apikey, Authorization: Bearer <KEY>         │
 └───────────┬─────────────────────────────────────────────┘
             │ HTTPS PostgREST
             ▼
 ┌────────────────────────┐
 │  Supabase PostgreSQL   │
 └────────────────────────┘
```

---

## 1. Supabase Database Setup

1. Open your project on [Supabase Dashboard](https://supabase.com/dashboard).
2. Navigate to **SQL Editor** -> **New Query**.
3. Copy and run the contents of [`supabase_schema.sql`](supabase_schema.sql):

```sql
CREATE TABLE IF NOT EXISTS public.youtube_shorts_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id TEXT NOT NULL,
    url TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    device_id TEXT,
    created_at TIMESTAMPTZ DEFAULT timezone('utc'::text, now()) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_shorts_video_id ON public.youtube_shorts_history(video_id);
CREATE INDEX IF NOT EXISTS idx_shorts_started_at ON public.youtube_shorts_history(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_shorts_device_id ON public.youtube_shorts_history(device_id);

ALTER TABLE public.youtube_shorts_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow anonymous client insert" 
ON public.youtube_shorts_history 
FOR INSERT 
TO anon 
WITH CHECK (true);

CREATE POLICY "Allow public read for analytics" 
ON public.youtube_shorts_history 
FOR SELECT 
TO anon, authenticated 
USING (true);
```

4. Retrieve your **Project URL** (e.g. `https://xyzproject.supabase.co`) and **Anon / Public Key** from **Project Settings -> API**.

---

## 2. Android Studio Project Setup

1. Open **Android Studio**.
2. Select **Open** and choose this project directory (`c:\Users\risas\Uni\project\track-youtube-shorts`).
3. Allow Gradle to sync dependencies (`OkHttp`, `Gson`, `Material Components`, `AndroidX`).
4. Build and run the app on an Android device or emulator with Microsoft Edge installed.

---

## 3. Configuring and Running the App

### Step 1: Configure Supabase
- Launch **Shorts Tracker** on your device.
- Enter your **Supabase URL** and **Supabase Anon Key**.
- Tap **Test Connection** to verify database connectivity.
- Tap **Save Settings**.

### Step 2: Enable the Accessibility Service
- Tap **Open Accessibility Settings** in the app.
- Locate **Edge YouTube Shorts Tracker Service** under *Downloaded Services / Installed Apps*.
- Toggle it **ON** and grant permissions.

> [!NOTE]
> **Android 13 & Android 14 Sideload Restriction**:
> If Android displays *"Restricted setting: For your security, this setting is currently unavailable"*:
> 1. Go to **Settings -> Apps -> Shorts Tracker**.
> 2. Tap the **three dots (⋮)** in the top right corner.
> 3. Tap **Allow restricted settings**.
> 4. Return to Accessibility settings and enable the service.

### Step 3: Test with Microsoft Edge
1. Open **Microsoft Edge** (`com.microsoft.emmx`).
2. Navigate to [youtube.com/shorts](https://youtube.com/shorts).
3. Watch a few Shorts for varying durations (e.g. 10s, 30s).
4. Switch back to **Shorts Tracker** to see live session logs and check the Supabase table for newly inserted records.

---

## Project Structure

```
track-youtube-shorts/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/tracker/youtubeshorts/
│       │   ├── MainActivity.java
│       │   ├── adapter/
│       │   │   └── SessionLogAdapter.java
│       │   ├── model/
│       │   │   └── ShortSession.java
│       │   ├── network/
│       │   │   ├── SupabaseClient.java
│       │   │   └── SupabaseConfig.java
│       │   └── service/
│       │       └── EdgeShortsAccessibilityService.java
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   └── item_log.xml
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── xml/
│               └── accessibility_service_config.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
├── supabase_schema.sql
└── README.md
```