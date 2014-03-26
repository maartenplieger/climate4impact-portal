package impactservice;

import java.io.FileOutputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.globus.myproxy.MyProxy;
import org.globus.myproxy.MyProxyException;
import org.gridforum.jgss.ExtendedGSSCredential;
//import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.Oid;

import tools.DebugConsole;
import tools.Tools;

/**
 * @author maartenplieger
 * 
 * Info:
 * http://www.globus.org/cog/distribution/1.2/api/org/globus/myproxy/MyProxy.html
 * Command line SLC retrieval:
 * myproxy-logon -s bvlpenes.knmi.nl -k https://pcmdi3.llnl.gov/esgcet/myopenid/<user>
 * 
 * To be able to login on ESGF openid providers, their trustroots need to be set. This can be done by adding the following arguments to ECLIPSE,
 * or to JAVA_OPTS in production environment:
 * 
 * (https://meteo.unican.es/trac/wiki/ESGF-Security)
 * 
 * wget https://rainbow.llnl.gov/dist/certs/esg-truststore.ts
 * 
 * In: Run --> Run Configurations --> Arguments --> VM Arguments, add:
 * -Djavax.net.ssl.trustStore="<pathto>esg-truststore.ts" -Djavax.net.ssl.trustStorePassword="changeit"
 * Or add these arguments to JAVA_OPTS in production environment
 * 
 * 
 * # Or Create a custom java trust store file with a shell script:
 * echo | openssl s_client -connect pcmdi9.llnl.gov:443  2>&1 | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p'  > pcmdi9.llnl.gov
 * keytool -delete -alias pcmdi9.llnl.gov  -keystore openidcerts.ts -storepass changeit
 * keytool -import -v -trustcacerts -alias pcmdi9.llnl.gov -file pcmdi9.llnl.gov -keystore openidcerts.ts -storepass changeit -noprompt
 * 
 * 
 * 
 * 
 */

public class LoginManager {
  static Vector<ImpactUser> users = new Vector<ImpactUser>();
  
  /**
   * Retrieves a SLC (short lived credential) from the SLCS and stores it in the users home directory 
   * @param user User object with identifier and home directory set
   * @throws Exception
   */
  public synchronized static void getCredential(ImpactUser user) throws Exception{
    
    if(Configuration.GlobalConfig.isInOfflineMode()==true){
      DebugConsole.println("offline mode");
      return;
    }
    MyProxy myProxy = new MyProxy(Configuration.LoginConfig.getMyProxyServerHost(),Configuration.LoginConfig.getMyProxyServerPort());
    //myProxy.setHost(Configuration.LoginConfig.getMyProxyServerHost());
    //myProxy.setPort(Configuration.LoginConfig.getMyProxyServerPort());
    
    DebugConsole.println("Setting proxy host:port as '"+Configuration.LoginConfig.getMyProxyServerHost()+":"+Configuration.LoginConfig.getMyProxyServerPort()+"'");
    //GSSCredential retcred = myProxy.get(null,user.id, Configuration.LoginConfig.getMyProxyDefaultPassword(), 60*60*24);
    try {
      String userName = Configuration.LoginConfig.getMyProxyDefaultUserName();
      if(userName == null){
        userName = user.id;
      }
      
      DebugConsole.println("Setting username to "+userName+":"+Configuration.LoginConfig.getMyProxyDefaultPassword());
      
      ExtendedGSSCredential cred= (ExtendedGSSCredential) myProxy.get(userName, Configuration.LoginConfig.getMyProxyDefaultPassword(), 60*60*24);
     
      try {
        DebugConsole.println(cred.getName().toString());
       // DebugConsole.println(""+cred.getRemainingLifetime());
        
        Oid [] mechs = cred.getMechs();
        if (mechs != null) {
                for (int i = 0; i < mechs.length; i++)
                        DebugConsole.println(mechs[i].toString());
        }
        
        //Export credential to file
        byte [] data = cred.export(ExtendedGSSCredential.IMPEXP_OPAQUE);
        FileOutputStream out = new FileOutputStream(user.certificateFile);
        out.write(data);
        out.close();
        // release system resources held by the credential
        cred.dispose();

      } catch (Exception e) {
        DebugConsole.printStackTrace(e);
        throw new Exception("LoginManager: Unable to write credential");
      }
    } catch (MyProxyException e) {
      String msg="Unable to get credential for "+user.id;
      DebugConsole.errprintln(msg);
      DebugConsole.printStackTrace(e);
      throw new Exception("LoginManager: "+msg);
    }
    DebugConsole.println("Credentials for user "+user.id+" retrieved");
  }
  
 /**
  * Get the user object based on the http session
  * @param request The httpservletrequest
  * @return The user object or null when a redirect is requested.
  * @throws Exception
  */
  public static ImpactUser getUser(HttpServletRequest request, HttpServletResponse response) throws Exception {
  
    HttpSession session = request.getSession();
    String id=(String) session.getAttribute("openid_identifier");
    if(Configuration.GlobalConfig.isInOfflineMode()==true){
      id=Configuration.GlobalConfig.getDefaultUser();
    }
    
    if(id == null && response != null){
      //DebugConsole.println("Trying to getUser from CERT");
      /*if(request.isSecure() == false){
        String localContext = Configuration.getHomeURL();
        String remoteContext = request.getContextPath();
        if(localContext.equals(remoteContext)&&1==2){
          DebugConsole.println("Forwarding: "+localContext+ " == "+remoteContext);
                
          
          
          DebugConsole.println("No user info in SESSION, forcing HTTPS --> redirecting to "+Configuration.GlobalConfig.getServerHTTPSURL()+request.getRequestURI());
          response.setStatus(302);
          response.setHeader( "Location", Configuration.GlobalConfig.getServerHTTPSURL()+request.getRequestURI()+"?"+request.getQueryString());
          response.setHeader( "Connection", "close" );
          return null;
        }*/
        //if(request.isSecure() == true){
          //DebugConsole.println("Trying to get user info from X509 cert");
          
          String CertOpenIdIdentifier = null;
          //org.apache.catalina.authenticator.SSLAuthenticator
          X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
          if (null != certs && certs.length > 0) {
            X509Certificate cert = certs[0];
            
              String subjectDN = cert.getSubjectDN().toString();
              DebugConsole.println("getSubjectDN: "+subjectDN);
              String[] dnItems = subjectDN.split(", ");
              for(int j=0;j<dnItems.length;j++){
                int CNIndex = dnItems[j].indexOf("CN");
                if(CNIndex != -1){
                  CertOpenIdIdentifier = dnItems[j].substring("CN=".length()+CNIndex);
                }
              }
              //DebugConsole.println("CertOpenIdIdentifier=["+CertOpenIdIdentifier+"]");
              
          }else{
            String message = "No user information available from either session or x509\n";
            DebugConsole.errprintln(message);
            /*response.setStatus(403);
            response.getOutputStream().println(message);
            throw new Exception("You are not logged in...");*/
          }
          
          
          if(  CertOpenIdIdentifier == null){
            String message = "No valid ESGF certificate provided: no user found.";
            DebugConsole.errprintln(message);
            /*response.setStatus(403);
            response.getOutputStream().println(message);
            throw new Exception(message);*/
          }else{
            id = CertOpenIdIdentifier;
          }
        }
     // }
   // }
      
    //DebugConsole.println("Getting user from session with id "+id);
    if(id == null)throw new Exception("You are not logged in...");
    ImpactUser user = getUser(id);
    return user;
  }
  
  public static ImpactUser getUser(HttpServletRequest request) throws Exception {
    return getUser(request,null);
  }

  /**
   * Get user based on his/hers userId
   * @param userId The userID, equal to the OpenID identifier
   * @return The user object
   */
  public synchronized static ImpactUser getUser(String userId){
    //DebugConsole.println("Looking up user "+userId);
    //Lookup the user in the vector list
    if(userId==null)return null;
    for(int j=0;j<users.size();j++){
      if(users.get(j).id.equals(userId)){
        ImpactUser user = users.get(j);
        //DebugConsole.println("Found existing user "+userId);
        return user;
      }
    }
    //The user was not found, so create a new user
    DebugConsole.println("Creating new user object for "+userId);
    ImpactUser user = new ImpactUser();
    user.id=userId;
    users.add(user);
    try {checkLogin(userId);} catch (Exception e) { }
    
    return user;
  }
  
  /**
   * Called upon succesfull login, handles and checks user ID
   * @param session
   * @throws Exception 
   */
  public synchronized static void checkLogin(String openIdIdentifier) throws Exception{

    DebugConsole.println("checkLogin "+openIdIdentifier);
    if(openIdIdentifier==null){
      DebugConsole.errprintln("No openIdIdentifier given");
    }
    ImpactUser user = getUser(openIdIdentifier);
    
    DebugConsole.println("Check login "+user.id);
    user.internalName = user.id.replace("http://", "");
    user.internalName = user.internalName.replace("https://", "");
    user.internalName = user.internalName.replaceAll("/", ".");
    DebugConsole.println("internalName = "+user.internalName);

    user.setWorkspace(Configuration.getImpactWorkspace()+user.internalName+"/");
    try {
      DebugConsole.println("Making dir "+user.getWorkspace());
      Tools.mkdir(user.getWorkspace());
      Tools.mkdir(user.getWorkspace()+"certs");
      user.certificateFile = user.getWorkspace()+"certs/"+"creds.pem";
    } catch (IOException e) {
      DebugConsole.errprintln(e.getMessage());
      user.credentialError=true;
      throw new Exception("Unable to create credential for user, server misconfiguration:"+user.id+"\n"+e.getMessage());
    }
    try{
    getCredential(user);
    }catch(Exception e){
      user.credentialError=true;
      throw new Exception("Unable to get credential for user "+user.id+"\n"+e.getMessage());
    }
    
    createNCResourceFile(user);
    //createFontConfigFile(user);
    user.configured = true;
  }
  
  /**
   * Create NetCDF .httprc or .dodsrc resource file and store it in the users home directory
   * @param user The user object
   * @throws IOException
   */
  public synchronized static void createNCResourceFile(ImpactUser user) throws IOException{
    //DebugConsole.println("createNCResourceFile for user "+user.id);
    /*
      .httprc/.dodsrc file contents:
      HTTP.SSL.VALIDATE=0
      HTTP.COOKIEJAR=.dods_cookies
      HTTP.SSL.CERTIFICATE=<slc.pem>
      HTTP.SSL.KEY=<slc.pem>
      HTTP.SSL.CAPATH=<esg_trusted_certificates>
   */  

    String fileContents =
        "HTTP.SSL.VALIDATE=0\n"
        +"HTTP.COOKIEJAR="+user.getWorkspace()+"/.dods_cookies\n"
        +"HTTP.SSL.CERTIFICATE="+user.certificateFile+"\n"
        +"HTTP.SSL.KEY="+user.certificateFile+"\n"
        //+"HTTP.SSL.SSLv3="+user.certificateFile+"\n"
        //+"HTTP.SSL.CAPATH="+ Configuration.getImpactWorkspace()+"/esg_trusted_certificates/";
        +"HTTP.SSL.CAPATH="+ Configuration.LoginConfig.getTrustRootsLocation();//+"/esg_trusted_certificates/";
    DebugConsole.println("createNCResourceFile for user "+user.id+":\n"+fileContents);
    Tools.writeFile(user.getWorkspace()+"/.httprc", fileContents) ;
    Tools.writeFile(user.getWorkspace()+"/.dodsrc", fileContents) ;
 }
 
}
