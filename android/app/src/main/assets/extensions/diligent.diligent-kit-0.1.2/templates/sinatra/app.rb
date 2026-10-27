# {{label}}: a small web app with Sinatra.
require "sinatra"
require "json"
require "time"

set :bind, "127.0.0.1"
set :port, 4567
set :server, "webrick"

get "/" do
  "<h1>Hello from {{label}}</h1><p>Edit app.rb, then restart the run.</p><p><a href='/api/time'>/api/time</a></p>"
end

get "/api/time" do
  content_type :json
  { now: Time.now.iso8601 }.to_json
end
