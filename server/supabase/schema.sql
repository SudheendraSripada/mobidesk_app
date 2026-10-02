-- ==============================================================================
-- MobiDesk Supabase Database Schema & Row-Level Security (RLS)
-- File: server/supabase/schema.sql
-- ==============================================================================
-- IMPORTANT ARCHITECTURE & SECURITY NOTES:
-- 1. Students authenticate using Supabase Auth (auth.users).
-- 2. The students table uses auth.uid() as primary key (1:1 with auth.users).
-- 3. Row Level Security (RLS) strictly restricts students to viewing only their own rows.
-- 4. NEVER store VM passwords or RDP credentials in Supabase! RDP credentials live
--    EXCLUSIVELY within Apache Guacamole connection configurations.
-- 5. Read SUPABASE_URL and SUPABASE_ANON_KEY from gitignored environment configuration.
-- ==============================================================================

-- 1. Enable UUID extension if not already present
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. Create 'students' table
CREATE TABLE IF NOT EXISTS public.students (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    roll_no TEXT NOT NULL UNIQUE,
    college TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- 3. Create 'vms' table
CREATE TABLE IF NOT EXISTS public.vms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    guac_connection_id TEXT NOT NULL,
    hostname TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- 4. Create 'student_vm_access' junction table
CREATE TABLE IF NOT EXISTS public.student_vm_access (
    student_id UUID NOT NULL REFERENCES public.students(id) ON DELETE CASCADE,
    vm_id UUID NOT NULL REFERENCES public.vms(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    PRIMARY KEY (student_id, vm_id)
);

-- ==============================================================================
-- Row Level Security (RLS) Configuration
-- ==============================================================================

-- Enable RLS on all tables
ALTER TABLE public.students ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vms ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.student_vm_access ENABLE ROW LEVEL SECURITY;

-- Drop existing policies if re-running
DROP POLICY IF EXISTS "Students can view own profile" ON public.students;
DROP POLICY IF EXISTS "Students can view own VM access mapping" ON public.student_vm_access;
DROP POLICY IF EXISTS "Students can view assigned VMs" ON public.vms;

-- Policy 1: Students can only view their own profile
CREATE POLICY "Students can view own profile"
    ON public.students
    FOR SELECT
    USING (auth.uid() = id);

-- Policy 2: Students can only view their own VM access rows
CREATE POLICY "Students can view own VM access mapping"
    ON public.student_vm_access
    FOR SELECT
    USING (auth.uid() = student_id);

-- Policy 3: Students can only view VMs assigned to them
CREATE POLICY "Students can view assigned VMs"
    ON public.vms
    FOR SELECT
    USING (
        id IN (
            SELECT vm_id
            FROM public.student_vm_access
            WHERE student_id = auth.uid()
        )
    );

-- ==============================================================================
-- Realistic Seed Data Examples
-- ==============================================================================

-- Seed Virtual Machines
INSERT INTO public.vms (id, name, guac_connection_id, hostname)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Windows 11 Student Lab 01', '1', '10.0.1.101'),
    ('a0000000-0000-0000-0000-000000000002', 'Windows 11 Student Lab 02', '2', '10.0.1.102'),
    ('a0000000-0000-0000-0000-000000000003', 'Windows 10 Engineering Workstation', '3', '10.0.1.103')
ON CONFLICT (id) DO NOTHING;

-- Note on inserting student profiles:
-- Once a user signs up via Supabase Auth (e.g. auth.users row with id 'd0000000-0000-0000-0000-000000000001'):
-- INSERT INTO public.students (id, name, roll_no, college)
-- VALUES ('d0000000-0000-0000-0000-000000000001', 'Alex Mercer', 'CS-2024-042', 'School of Engineering & Technology');
--
-- INSERT INTO public.student_vm_access (student_id, vm_id)
-- VALUES ('d0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001');

-- Helper Trigger to automatically create student profile on Supabase auth signup (optional)
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.students (id, name, roll_no, college)
    VALUES (
        new.id,
        COALESCE(new.raw_user_meta_data->>'name', 'Student User'),
        COALESCE(new.raw_user_meta_data->>'roll_no', 'CS-' || substring(new.id::text from 1 for 6)),
        COALESCE(new.raw_user_meta_data->>'college', 'School of Engineering & Technology')
    )
    ON CONFLICT (id) DO NOTHING;
    RETURN new;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
