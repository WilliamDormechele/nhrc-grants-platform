/** Keep browser requests on the signed-in site origin; the API stays private. */
const apiOrigin=process.env.API_INTERNAL_URL||"http://api:8080";
export default {async rewrites(){return [{source:"/api/:path*",destination:apiOrigin+"/api/:path*"}]}};
