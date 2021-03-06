package org.mifos.sms.scheduler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.mifos.sms.data.ConfigurationData;
import org.mifos.sms.domain.SmsDeliveryReport;
import org.mifos.sms.domain.SmsDeliveryReportRepository;
import org.mifos.sms.domain.SmsMessageStatusType;
import org.mifos.sms.domain.SmsOutboundMessage;
import org.mifos.sms.domain.SmsOutboundMessageRepository;
import org.mifos.sms.gateway.infobip.SmsGatewayConfiguration;
import org.mifos.sms.gateway.infobip.SmsGatewayImpl;
import org.mifos.sms.gateway.infobip.SmsGatewayMessage;
import org.mifos.sms.service.ReadConfigurationService;
import org.mifos.sms.service.SmppSessionLifecycle;
import org.mifos.sms.smpp.session.SmppSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SmsOutboundMessageScheduledJobServiceImpl implements SmsOutboundMessageScheduledJobService {
    private final SmsOutboundMessageRepository smsOutboundMessageRepository;
    private SmsGatewayImpl smsGatewayImpl;
    private final static Logger logger = LoggerFactory.getLogger(SmsOutboundMessageScheduledJobServiceImpl.class);
    private final ReadConfigurationService readConfigurationService;
    private final SmsGatewayConfiguration smsGatewayConfiguration;
    private SmppSessionFactoryBean smppSessionFactoryBean;
    private SmppSessionLifecycle smppSessionLifecycle;
    private final SmsDeliveryReportRepository smsDeliveryReportRepository;
    
    @Autowired
    public SmsOutboundMessageScheduledJobServiceImpl(SmsOutboundMessageRepository smsOutboundMessageRepository,
    		final ReadConfigurationService readConfigurationService, 
    		final SmsDeliveryReportRepository smsDeliveryReportRepository) {
        this.readConfigurationService = readConfigurationService;
        
        final Collection<ConfigurationData> configurationDataCollection = this.readConfigurationService.findAll();
        
        smsGatewayConfiguration = new SmsGatewayConfiguration(configurationDataCollection);
        
    	this.smsOutboundMessageRepository = smsOutboundMessageRepository;
    	this.smsDeliveryReportRepository = smsDeliveryReportRepository;
    	
    	if (this.isSmppEnabledInSmsGatewayPropertiesFile()) {
    	    this.smppSessionFactoryBean = new SmppSessionFactoryBean(smsGatewayConfiguration, smsDeliveryReportRepository);
            this.smsGatewayImpl = new SmsGatewayImpl(smppSessionFactoryBean);
            this.smppSessionLifecycle = smppSessionFactoryBean.getSmppSessionLifecycle();
    	}
    }

	@Override
	@Transactional
	@Scheduled(fixedDelay = 60000)
	public void sendMessages() {
	    
	    // check if the scheduler is enabled
		if(smsGatewayConfiguration.outboundMessageSchedulerIsEnabled() && 
		        this.isSchedulerEnabledInSmsGatewayPropertiesFile() && 
		        this.isSmppEnabledInSmsGatewayPropertiesFile()) {
		    
		    if(smppSessionLifecycle.isActive()) {
				Pageable pageable = new PageRequest(0, getMaximumNumberOfMessagesToBeSent());
				List<SmsOutboundMessage> smsOutboundMessages = smsOutboundMessageRepository.findByDeliveryStatus(SmsMessageStatusType.PENDING.getValue(), pageable);
				
				// only proceed if there are pending messages
		        if((smsOutboundMessages != null) && smsOutboundMessages.size() > 0) {
		            
		            for(SmsOutboundMessage smsOutboundMessage : smsOutboundMessages) {
		                SmsGatewayMessage smsGatewayMessage = new SmsGatewayMessage(smsOutboundMessage.getId(), 
		                        smsOutboundMessage.getExternalId(), smsOutboundMessage.getSourceAddress(), 
		                        smsOutboundMessage.getMobileNumber(), smsOutboundMessage.getMessage());
		                
		                // send message to SMS message gateway
		                smsGatewayMessage = smsGatewayImpl.sendMessage(smsGatewayMessage);
		                
		                // update the "submittedOnDate" property of the SMS message in the DB
		                smsOutboundMessage.setSubmittedOnDate(new Date());
		                
		                // check if the returned SmsGatewayMessage object has an external ID
		                if(!StringUtils.isEmpty(smsGatewayMessage.getExternalId())) {
		                    
		                    // update the external ID of the SMS message in the DB
		                    smsOutboundMessage.setExternalId(smsGatewayMessage.getExternalId());
		                    
		                    // update the status of the SMS message in the DB
		                    smsOutboundMessage.setDeliveryStatus(SmsMessageStatusType.SENT);
		                    
		                } else {
		                    // update the status of the SMS message in the DB
		                    smsOutboundMessage.setDeliveryStatus(SmsMessageStatusType.FAILED);
		                }
		                
		                smsOutboundMessageRepository.save(smsOutboundMessage);
		            }
		        }
		        
			} else {
			    // reconnect
			    smppSessionLifecycle.restartSmppSession();
			}
		}
	}
	
	private boolean isSmppEnabledInSmsGatewayPropertiesFile() {
	    // smpp is disabled by default
        boolean isEnabled = false;
        Properties properties = new Properties();
        InputStream propertiesInputStream = null;
        File catalinaBaseConfDirectory = null;
        File propertiesFile = null;
        String smppDotEnablePropertyValue = null;
        
        try {
            // create a new File instance for the catalina base conf directory
            catalinaBaseConfDirectory = new File(System.getProperty("catalina.base"), "conf");
            
            // create a new File instance for the properties file
            propertiesFile = new File(catalinaBaseConfDirectory, "sms-gateway.properties");
            
            // create file inputstream to the properties file
            propertiesInputStream = new FileInputStream(propertiesFile);
            
            // read property list from input stream 
            properties.load(propertiesInputStream);
            
            smppDotEnablePropertyValue = properties.getProperty("smpp.enabled");
            
            // make sure it isn't blank, before trying to parse the string as boolean
            if (StringUtils.isNoneBlank(smppDotEnablePropertyValue)) {
                isEnabled = Boolean.parseBoolean(smppDotEnablePropertyValue); 
            }
            
        } catch (FileNotFoundException ex) { } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            
        } finally {
            if (propertiesInputStream != null) {
                try {
                    propertiesInputStream.close();
                    
                }  catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        
        return isEnabled;
	}
	
	/**
	 * check if the scheduler.enables property in the "/var/lib/tomcat7/conf/sms-gateway.properties" file is set to true
     * 
     * @return boolean true if value is true, else false
	 */
	private boolean isSchedulerEnabledInSmsGatewayPropertiesFile() {
	    // scheduler is disabled by default
        boolean isEnabled = false;
        Properties quartzProperties = new Properties();
        InputStream quartzPropertiesInputStream = null;
        File catalinaBaseConfDirectory = null;
        File quartzPropertiesFile = null;
        String scheduleDotEnablePropertyValue = null;
        
        try {
            // create a new File instance for the catalina base conf directory
            catalinaBaseConfDirectory = new File(System.getProperty("catalina.base"), "conf");
            
            // create a new File instance for the quartz properties file
            quartzPropertiesFile = new File(catalinaBaseConfDirectory, "sms-gateway.properties");
            
            // create file inputstream to the quartz properties file
            quartzPropertiesInputStream = new FileInputStream(quartzPropertiesFile);
            
            // read property list from input stream 
            quartzProperties.load(quartzPropertiesInputStream);
            
            scheduleDotEnablePropertyValue = quartzProperties.getProperty("scheduler.enabled");
            
            // make sure it isn't blank, before trying to parse the string as boolean
            if (StringUtils.isNoneBlank(scheduleDotEnablePropertyValue)) {
                isEnabled = Boolean.parseBoolean(scheduleDotEnablePropertyValue); 
            }
            
        } catch (FileNotFoundException ex) { } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            
        } finally {
            if (quartzPropertiesInputStream != null) {
                try {
                    quartzPropertiesInputStream.close();
                    
                }  catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        
        return isEnabled;
	}
	
	/** 
	 * Get the maximum number of messages to be sent to the SMS gateway
	 * 
	 * TODO this should be configurable, add to c_configuration
	 **/
	private int getMaximumNumberOfMessagesToBeSent() {
		return 5000;
	}

    @Override
    @Transactional
    @Scheduled(fixedDelay = 60000)
    public void processSmsGatewayDeliveryReports() {
        if (this.isSmppEnabledInSmsGatewayPropertiesFile()) {
            if(smppSessionLifecycle.isActive()) {
                final Page<SmsDeliveryReport> smsDeliveryReportPage = this.smsDeliveryReportRepository.findAll(new PageRequest(0, 5000));
                
                for (SmsDeliveryReport smsDeliveryReport : smsDeliveryReportPage) {
                    this.processSmsDeliveryReport(smsDeliveryReport, 0);
                }
                
            } else {
                this.smppSessionLifecycle.restartSmppSession();
            }
        }
    }
    
    private void processSmsDeliveryReport(final SmsDeliveryReport smsDeliveryReport, 
            Integer smsOutboundMessageRetrievalAttempts) {
        final String messageId = smsDeliveryReport.getMessageId();
        final SmsMessageStatusType statusType = SmsMessageStatusType.instance(smsDeliveryReport.getSmsDeliveryStatus());
        
        // get the SmsMessage object from the DB
        final SmsOutboundMessage smsOutboundMessage = this.smsOutboundMessageRepository.findByExternalId(messageId);
        
        // increment the number of retrieval attempts
        smsOutboundMessageRetrievalAttempts++;
        
        if(smsOutboundMessage != null) {
            // update the status of the SMS message
            smsOutboundMessage.setDeliveryStatus(statusType);
            
            switch(smsDeliveryReport.getSmsDeliveryStatus()) {
                case DELIVERED:
                    // update the delivery date of the SMS message
                    smsOutboundMessage.setDeliveredOnDate(smsDeliveryReport.getDoneOnDate());
                    break;
                    
                default:
                    break;
            }
            
            // save the "SmsOutboundMessage" entity
            this.smsOutboundMessageRepository.saveAndFlush(smsOutboundMessage);
            
            logger.info("SMS message with external ID '" + messageId
                    + "' successfully updated. Status set to: " + smsOutboundMessage.getDeliveryStatus().
                    toString());
            
            // remove the delivery report from the queue
            this.smsDeliveryReportRepository.delete(smsDeliveryReport);
            
        } else {
            if (smsOutboundMessageRetrievalAttempts <= 3) {
                try {
                    // sleep for 5 seconds
                    Thread.sleep(5000L);
                    
                    this.processSmsDeliveryReport(smsDeliveryReport, smsOutboundMessageRetrievalAttempts);
                    
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                
            } else {
                // remove the delivery report from the queue
                this.smsDeliveryReportRepository.delete(smsDeliveryReport);
            }
        }
    }
}
