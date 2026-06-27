package com.tradewise.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Fund limit response from Dhan API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundLimitResponse {

    @JsonAlias({"availabelBalance", "availableBalance"})
    private double availableBalance;

    private double sodLimit;
    private double collateralAmount;

    @JsonAlias({"receiveableAmount", "receivableAmount"})
    private double receivableAmount;

    private double utilizedAmount;
    private double blockedPayoutAmount;
    private double withdrawableBalance;

    @JsonAlias({"dhanClietId", "dhanClientId"})
    private String dhanClientId;

    // ... existing code ...

    public double getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(double availableBalance) {
        this.availableBalance = availableBalance;
    }

    public double getSodLimit() {
        return sodLimit;
    }

    public void setSodLimit(double sodLimit) {
        this.sodLimit = sodLimit;
    }

    public double getCollateralAmount() {
        return collateralAmount;
    }

    public void setCollateralAmount(double collateralAmount) {
        this.collateralAmount = collateralAmount;
    }

    public double getReceivableAmount() {
        return receivableAmount;
    }

    public void setReceivableAmount(double receivableAmount) {
        this.receivableAmount = receivableAmount;
    }

    public double getUtilizedAmount() {
        return utilizedAmount;
    }

    public void setUtilizedAmount(double utilizedAmount) {
        this.utilizedAmount = utilizedAmount;
    }

    public double getBlockedPayoutAmount() {
        return blockedPayoutAmount;
    }

    public void setBlockedPayoutAmount(double blockedPayoutAmount) {
        this.blockedPayoutAmount = blockedPayoutAmount;
    }

    public double getWithdrawableBalance() {
        return withdrawableBalance;
    }

    public void setWithdrawableBalance(double withdrawableBalance) {
        this.withdrawableBalance = withdrawableBalance;
    }

    public String getDhanClientId() {
        return dhanClientId;
    }

    public void setDhanClientId(String dhanClientId) {
        this.dhanClientId = dhanClientId;
    }
}

