-- ============================================================================
-- Supabase PostgreSQL Schema for YouTube Shorts Tracker
-- ============================================================================
-- Run this script in the Supabase SQL Editor:
-- https://supabase.com/dashboard/project/_/sql

-- 1. Create the table for storing Shorts watch sessions
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

-- 2. Create indices for performant analytical queries
CREATE INDEX IF NOT EXISTS idx_shorts_video_id ON public.youtube_shorts_history(video_id);
CREATE INDEX IF NOT EXISTS idx_shorts_started_at ON public.youtube_shorts_history(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_shorts_device_id ON public.youtube_shorts_history(device_id);

-- 3. Enable Row Level Security (RLS)
ALTER TABLE public.youtube_shorts_history ENABLE ROW LEVEL SECURITY;

-- 4. Create RLS Policies
-- Allow anonymous inserts from the Android client using the anon API key
CREATE POLICY "Allow anonymous client insert" 
ON public.youtube_shorts_history 
FOR INSERT 
TO anon 
WITH CHECK (true);

-- Allow authenticated users or dashboard to view records
CREATE POLICY "Allow public read for analytics" 
ON public.youtube_shorts_history 
FOR SELECT 
TO anon, authenticated 
USING (true);

-- Comment on table
COMMENT ON TABLE public.youtube_shorts_history IS 'Stores individual YouTube Shorts viewing sessions captured from mobile browser';
