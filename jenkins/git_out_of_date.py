'''
Created on Jun 28, 2017

@author: Viachaslau_Kabak
'''

import os, subprocess
from datetime import datetime
MAILFROMADDR = "jenkins@server.by"
SMTPSERVERNAME = 'smtp.server.by'
daysToCheck=int(os.getenv("DAYS", "30"))                            #var in old days
cc_addresses=os.getenv("mailcc")        # cc mail list

msgTemplate="""Dear %s,
there are branches in Git that are %s days old. Could you please check and delete them if needed."""

def getAll():
    allNotes=subprocess.check_output(r'cd $WORKSPACE; git for-each-ref --sort=authordate --format="%(refname:short)...%(authordate:short)...%(authoremail)...%(authorname)" refs/remotes/', universal_newlines=True, shell=True)
    return [i.split("...") for i in allNotes.split("\n") if i]

def getOutdatedItems():
    return [i for i in getAll() if (datetime.now().date() - datetime.strptime(i[1], '%Y-%m-%d').date()).days >= daysToCheck]

def emailParse(mail):
    return mail.strip("<>")

def sortListByEmail():
    toReturn={}
    for item in getOutdatedItems():
        mail=emailParse(item[2]).lower()
        if mail in toReturn.keys():
            toReturn[mail].append(removeEmailFromList(item))
        else:
            toReturn.setdefault(mail, [])
            toReturn[mail].append(removeEmailFromList(item))
    return toReturn

def removeEmailFromList(itemList):
    itemList.pop(2)
    return itemList

def sendEmail(toAddr, subject, body):
    fromAddr = MAILFROMADDR
    if toAddr.count("@")==0:
        toAddr="wrong_address@wrong.com"
    import smtplib
    from email.mime.multipart import MIMEMultipart
    from email.mime.text import MIMEText
    print "Sending email to {0}".format(toAddr) 
    msg = MIMEMultipart()
    msg['Subject'] = subject
    msg['From'] = fromAddr
    msg['To'] = toAddr
    sendAddr=toAddr
    if cc_addresses is not None:
        msg['Cc'] = cc_addresses
        if toAddr:
            sendAddr=",".join([toAddr, cc_addresses])
        else:
            sendAddr=cc_addresses
    if toAddr=="wrong_address@wrong.com":
        body+="\n\nPlease correct email address in your local git client"
    if toAddr.count("@epam.com")==0:
        body+="\n\nPlease set EPAM email address in your local git client"
    msg.attach(MIMEText(body))
   
    # Send the message via our SMTP server
    s = smtplib.SMTP(SMTPSERVERNAME)
    s.sendmail(fromAddr, sendAddr.split(","), msg.as_string())
    s.quit()
    
    
def createEmailBody(branchesList):
    msg=msgTemplate %(branchesList[0][-1], str(daysToCheck))
    msg+="\n\n"
    for item in branchesList:
        msg+="{0}\t\t\t{1}\n".format(item[0], item[1])
    return msg

dictValues=sortListByEmail()
for key, value in dictValues.items():
    sendEmail(key, "Out-of-date branches in Git", createEmailBody(dictValues[key]))
    print key, value


