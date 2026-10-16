-- An examination that may only be sat in the CPIntel desktop app.
--
-- Off by default, and decided per examination by its admin: whether a college requires the
-- desktop app is the college's call, and a lab whose machines do not have it yet must still be
-- able to run a paper. When on, the examination password signs a candidate in only from the
-- desktop app, and a session renewed from anywhere else is ended. Changeable while the paper
-- runs, so an invigilator can lift it if the app fails on the lab's machines.
ALTER TABLE group_contests ADD COLUMN desktop_app_required BOOLEAN NOT NULL DEFAULT FALSE;
