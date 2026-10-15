CREATE INDEX notifications_visible_user_created ON notifications(user_id,created_at DESC) WHERE in_app_visible;
CREATE INDEX notifications_unread_user ON notifications(user_id) WHERE in_app_visible AND read_at IS NULL;
