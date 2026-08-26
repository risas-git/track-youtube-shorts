-- Run this SQL in your Supabase SQL Editor to delete the old non-Shorts records:
DELETE FROM public.youtube_shorts_history
WHERE video_id LIKE 'title_%'
   OR video_id = 'yurugakuto'
   OR title LIKE '%Übersetzung%'
   OR title LIKE '%Tastatur%'
   OR title LIKE '%play Short%';
