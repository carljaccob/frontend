define([
    'common/utils/config'
], function () {
    return function () {
        this.id = 'VideoButtonDuration';
        this.start = '2016-09-19';
        this.expiry = '2016-10-07';
        this.author = 'Chris J Clarke';
        this.description = 'Test whether adding duration to the play button increases plays';
        this.showForSensitive = true;
        this.audience = 0.20;
        this.audienceOffset = 0.15;
        this.successMeasure = 'No significant difference in clicks between the variant and control';
        this.audienceCriteria = 'All users';
        this.dataLinkNames = '';
        this.idealOutcome = 'Video plays in the control group is not more than 2% higher';

        this.canRun = function () {
          return document.getElementsByClassName('gu-media-wrapper--video').length > 0;
        };


        this.variants = [
            {
                id: 'control',
                test: function () {}
            },
            {
                id: 'video-button-duration',
                test: function () {}
            }
        ];
    };
});
