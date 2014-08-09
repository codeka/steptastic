
import pickle

from google.appengine.ext import db


class StepCount():
  def __init__(self, lat, lng, timestamp, count):
    self.lat = lat
    self.lng = lng
    self.timestamp = timestamp
    self.count = count


class DailyStepCount(db.Model):
  """We store a complete day's worth of step counts in a single entity.

  This does make things slightly more complicated, but the storage cost and time spend writing individual entries would
  make it prohibitively expensive storing each minutes count as it's own entity."""
  user = db.StringProperty()
  date = db.DateProperty()
  blob = db.BlobProperty()

  @staticmethod
  def get_daily_steps(email, dt):
    dsc = DailyStepCount.all().filter('user', email).filter('date', dt).fetch(1)
    if not dsc:
      return []
    return pickle.loads(dsc[0].blob)

  @staticmethod
  def append_steps(email, dt, steps):
    dsc = DailyStepCount.all().filter('user', email).filter('date', dt).fetch(1)
    existing_steps = []
    if not dsc:
      dsc = DailyStepCount(date=dt, user=email)
    else:
      existing_steps = pickle.loads(dsc.blob)
    for step_count in steps:
      was_existing = False
      for existing in existing_steps:
        if existing.timestamp == step_count.timestamp:
          was_existing = True
          existing.count += step_count.count
          if existing.lat == 0.0 or existing.lng == 0.0:
            existing.lat = step_count.lat
            existing.lng = step_count.lng
      if not was_existing:
        existing_steps.append(step_count)
    dsc.blob = pickle.dumps(existing_steps)
    dsc.put()


