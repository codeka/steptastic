
import datetime

import endpoints
from protorpc import messages
from protorpc import message_types
from protorpc import remote

import model

WEB_CLIENT_ID = '988087637760-6rhh5v6lhgjobfarparsomd4gectmk1v.apps.googleusercontent.com'
DEBUG_CLIENT_ID = '988087637760-hq543kfeigaa3dilqc227qjbqbucf75q.apps.googleusercontent.com'
RELEASE_CLIENT_ID = '988087637760-crlh1c8v61g2fgoo34ftri5g4he0mm4t.apps.googleusercontent.com'
ANDROID_AUDIENCE = WEB_CLIENT_ID

package = 'Sync'

class StepCount(messages.Message):
  lat = messages.FloatField(1)
  lng = messages.FloatField(2)
  timestamp = messages.IntegerField(3)
  count = messages.IntegerField(4)

class StepCountCollection(messages.Message):
  date = messages.IntegerField(1)
  steps = messages.MessageField(StepCount, 2, repeated=True)

@endpoints.api(name='syncsteps', version='v1',
               allowed_client_ids=[DEBUG_CLIENT_ID, RELEASE_CLIENT_ID,
                                   endpoints.API_EXPLORER_CLIENT_ID],
               audiences=[ANDROID_AUDIENCE],
               scopes=[endpoints.EMAIL_SCOPE])
class SyncStepsApi(remote.Service):
  @endpoints.method(StepCountCollection, message_types.VoidMessage,
                    path='steps', http_method='POST',
                    name='sync.putSteps')
  def put_steps(self, request):
    current_user = endpoints.get_current_user()
    dt = datetime.date.fromtimestamp(request.date / 1000)
    steps = []
    for step_count in request.steps:
      steps.append(model.StepCount(step_count.lat, step_count.lng, step_count.timestamp, step_count.count))
    model.DailyStepCount.append_steps(current_user.email(), dt, steps)
    return message_types.VoidMessage()

  DT_RESOURCE = endpoints.ResourceContainer(
                message_types.VoidMessage,
                dt=messages.IntegerField(1, variant=messages.Variant.INT64))

  @endpoints.method(DT_RESOURCE, StepCountCollection,
                    path='steps/{dt}', http_method='GET',
                    name='sync.getSteps')
  def get_steps(self, request):
    current_user = endpoints.get_current_user()
    dt = datetime.date.fromtimestamp(request.dt / 1000)
    steps_collection = StepCountCollection()
    steps = model.DailyStepCount.get_daily_steps(current_user.email(), dt)
    for step_count in steps:
      steps_collection.steps.append(StepCount(lat=step_count.lat, lng=step_count.lng, timestamp=step_count.timestamp,
                                              count=step_count.count))
    return steps_collection

app = endpoints.api_server([SyncStepsApi])
