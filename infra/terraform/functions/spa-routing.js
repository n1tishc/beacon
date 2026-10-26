// CloudFront Function (viewer request) on the app's S3 behavior. Built files have an extension
// (main-3F2A.js, favicon.ico); anything else is an Angular route, answered with the app's entry point.
function handler(event) {
  var request = event.request;
  var lastSegment = request.uri.substring(request.uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
