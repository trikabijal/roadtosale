package com.checkSheet.LDAP;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;


import com.checkSheet.DTO.UserDetails;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LdapAuth {

    private static DirContext ldapContext() throws Exception {
        Hashtable<String, String> env = new Hashtable<String, String>();
        return ldapContext(env);
    }

    private static DirContext ldapContext(Hashtable<String, String> env) throws Exception {
        env.put(Context.INITIAL_CONTEXT_FACTORY, Constants.contextFactory);
        env.put(Context.PROVIDER_URL, Constants.ldapURI);
        DirContext ctx = new InitialDirContext(env);
        return ctx;
    }

    public static String getUid(String user) throws Exception {
        DirContext ctx = ldapContext();
        String filter = "(uid=" + user + ")";
        SearchControls ctrl = new SearchControls();
        ctrl.setSearchScope(SearchControls.SUBTREE_SCOPE);
        NamingEnumeration answer = ctx.search("", filter, ctrl);
        String dn;
        if (answer.hasMore()) {
            SearchResult result = (SearchResult) answer.next();
            dn = result.getNameInNamespace();
        } else {
            dn = null;
        }
        answer.close();
        return dn;
    }

    public static boolean testBind(String dn, String password) throws Exception {
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, dn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        try {
            ldapContext(env);
        } catch (javax.naming.AuthenticationException e) {
            return false;
        }
        return true;
    }

    public static UserDetails getUserBasicAttributes(String user) throws Exception {
        DirContext ctx = ldapContext();
        UserDetails userDetails = new UserDetails();

        String name = user;
        String fName = "";
        String lName = "";
        String uId = "";
        String mailAddr = "";

        String filter = "(uid=" + user + ")";
        SearchControls ctrl = new SearchControls();
        ctrl.setSearchScope(SearchControls.SUBTREE_SCOPE);
        NamingEnumeration answer = ctx.search("", filter, ctrl);
        if (answer.hasMore()) {
            Attributes attrs = ((SearchResult) answer.next()).getAttributes();

            fName = attrs.get("givenname").get().toString();
            lName = attrs.get("sn").get().toString();
            name = fName + " " + lName;

            uId = attrs.get("uid").get().toString();
            mailAddr = attrs.get("mail") != null ? attrs.get("mail").get().toString() : null;

            userDetails.setFullName(name);
            userDetails.setIpn(uId);
            userDetails.setMailAddress(mailAddr);
            userDetails.setFirstName(fName);
            userDetails.setLastName(lName);
            System.out.println("name = " + name);
            System.out.println("fName = " + fName);
            System.out.println("lName = " + lName);
            System.out.println("uId = " + uId);
            System.out.println("mailAddr = " + mailAddr);
        }
        answer.close();
        return userDetails;
    }

    public static UserDetails checkWithLDAP(String ipn, String pwd) throws Exception {
        UserDetails usrLdap = new UserDetails();

        String dn = LdapAuth.getUid(ipn);
        System.out.printf("ipn : %s\n", ipn);
        System.out.printf("dn : %s\n", dn);
        if (dn != null) {
            usrLdap = LdapAuth.getUserBasicAttributes(ipn);
            System.out.printf("usrLdap : " + usrLdap);
            if (LdapAuth.testBind(dn, pwd)) {
                usrLdap = LdapAuth.getUserBasicAttributes(ipn);
                usrLdap.setAuthStatus(Constants.SUCCESS);
                LdapAuth.log.info(Constants.SUCCESS);
            } else {
                usrLdap.setAuthStatus(Constants.FAIL);
                LdapAuth.log.info(Constants.FAIL);
            }
        } else {
            usrLdap.setAuthStatus(Constants.FAIL);
            LdapAuth.log.info(Constants.FAIL);
        }

        return usrLdap;
    }

}