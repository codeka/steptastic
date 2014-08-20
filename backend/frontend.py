
import datetime
import jinja2
import os
import time
import webapp2

# This value gets incremented every time we deploy so that we can cache bust
# our static resources (css, js, etc)
RESOURCE_VERSION = 4

jinja = jinja2.Environment(loader=jinja2.FileSystemLoader(os.path.dirname(__file__)+'/tmpl'))


class BaseHandler(webapp2.RequestHandler):
  def render(self, tmpl_name, args):
    if not args:
      args = {}

    args['year'] = datetime.datetime.now().year

    if os.environ['SERVER_SOFTWARE'].startswith('Development'):
      args['is_development_server'] = True
      args['resource_version'] = int(time.time())
    else:
      args['is_development_server'] = False
      args['resource_version'] = RESOURCE_VERSION

    if tmpl_name[-4:] == ".txt":
      self.response.content_type = "text/plain"
    elif tmpl_name[-4:] == ".rss":
      self.response.content_type = "application/rss+xml"
    else:
      self.response.content_type = "text/html"

    tmpl = jinja.get_template(tmpl_name)
    self.response.out.write(tmpl.render(args))

  def error(self, code):
    """Handles errors. We have a custom 404 error page."""
    super(BaseHandler, self).error(code)
    if code == 404:
      self.render("404.html", {})


class MainPage(BaseHandler):
  def get(self):
    self.render('index.html', {})


app = webapp2.WSGIApplication([
    ('/', MainPage),
  ], debug=True)
