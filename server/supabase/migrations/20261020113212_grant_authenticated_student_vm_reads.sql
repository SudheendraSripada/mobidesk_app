-- Least-privilege Data API grants for authenticated student reads.
-- RLS policies continue to restrict each row; column privileges additionally
-- prevent students from selecting private VM hostnames.
GRANT USAGE ON SCHEMA public TO authenticated;

GRANT SELECT ON TABLE public.students TO authenticated;
GRANT SELECT ON TABLE public.student_vm_access TO authenticated;

REVOKE ALL ON TABLE public.vms FROM PUBLIC, anon, authenticated;
GRANT SELECT (id, name, guac_connection_id)
  ON TABLE public.vms TO authenticated;
